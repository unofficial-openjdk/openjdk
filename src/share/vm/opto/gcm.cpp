#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)gcm.cpp	1.251 07/05/17 15:58:45 JVM"
#endif
/*
 * Copyright 1997-2007 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *  
 */

// Portions of code courtesy of Clifford Click

// Optimization - Graph Style

#include "incls/_precompiled.incl"
#include "incls/_gcm.cpp.incl"

//----------------------------schedule_node_into_block-------------------------
// Insert node n into block b. Look for projections of n and make sure they
// are in b also.
void PhaseCFG::schedule_node_into_block( Node *n, Block *b ) {
  // Set basic block of n, Add n to b, 
  _bbs.map(n->_idx, b);
  b->add_inst(n);

  // After Matching, nearly any old Node may have projections trailing it.
  // These are usually machine-dependent flags.  In any case, they might
  // float to another block below this one.  Move them up.
  for (DUIterator_Fast imax, i = n->fast_outs(imax); i < imax; i++) {
    Node*  use  = n->fast_out(i);
    if (use->is_Proj()) {         
      Block* buse = _bbs[use->_idx];
      if (buse != b) {              // In wrong block?
        if (buse != NULL)
          buse->find_remove(use);   // Remove from wrong block
        _bbs.map(use->_idx, b);     // Re-insert in this block
        b->add_inst(use);
      }
    }
  }
}
    

//------------------------------schedule_pinned_nodes--------------------------
// Set the basic block for Nodes pinned into blocks
void PhaseCFG::schedule_pinned_nodes( VectorSet &visited ) {
  // Allocate node stack of size C->unique()+8 to avoid frequent realloc
  GrowableArray <Node *> spstack(C->unique()+8);
  spstack.push(_root);
  while ( spstack.is_nonempty() ) {
    Node *n = spstack.pop();
    if( !visited.test_set(n->_idx) ) { // Test node and flag it as visited
      if( n->pinned() && !_bbs.lookup(n->_idx) ) {  // Pinned?  Nail it down!
        Node *input = n->in(0);
        assert( input, "pinned Node must have Control" );
        while( !input->is_block_start() )
          input = input->in(0);
        Block *b = _bbs[input->_idx];  // Basic block of controlling input
        schedule_node_into_block(n, b);
      }
      for( int i = n->req() - 1; i >= 0; --i ) {  // For all inputs
        if( n->in(i) != NULL )
          spstack.push(n->in(i));
      }
    }
  }
}

#ifdef ASSERT
// Assert that new input b2 is dominated by all previous inputs.
// Check this by by seeing that it is dominated by b1, the deepest
// input observed until b2.
static void assert_dom(Block* b1, Block* b2, Node* n, Block_Array &bbs) {
  if (b1 == NULL)  return;
  assert(b1->_dom_depth < b2->_dom_depth, "sanity");
  Block* tmp = b2;
  while (tmp != b1 && tmp != NULL) {
    tmp = tmp->_idom;      
  }
  if (tmp != b1) {
    // Detected an unschedulable graph.  Print some nice stuff and die.
    tty->print_cr("!!! Unschedulable graph !!!");
    for (uint j=0; j<n->len(); j++) { // For all inputs
      Node* inn = n->in(j); // Get input
      if (inn == NULL)  continue;  // Ignore NULL, missing inputs
      Block* inb = bbs[inn->_idx];
      tty->print("B%d idom=B%d depth=%2d ",inb->_pre_order, 
                 inb->_idom ? inb->_idom->_pre_order : 0, inb->_dom_depth);
      inn->dump();
    }
    tty->print("Failing node: ");
    n->dump();
    assert(false, "unscheduable graph");
  }
}
#endif

static Block* find_deepest_input(Node* n, Block_Array &bbs) {
  // Find the last input dominated by all other inputs.
  Block* deepb           = NULL;        // Deepest block so far
  int    deepb_dom_depth = 0;
  for (uint k = 0; k < n->len(); k++) { // For all inputs
    Node* inn = n->in(k);               // Get input
    if (inn == NULL)  continue;         // Ignore NULL, missing inputs
    Block* inb = bbs[inn->_idx];
    assert(inb != NULL, "must already have scheduled this input");
    if (deepb_dom_depth < (int) inb->_dom_depth) {
      // The new inb must be dominated by the previous deepb.
      // The various inputs must be linearly ordered in the dom
      // tree, or else there will not be a unique deepest block.
      DEBUG_ONLY(assert_dom(deepb, inb, n, bbs));
      deepb = inb;                      // Save deepest block
      deepb_dom_depth = deepb->_dom_depth;
    }
  }
  assert(deepb != NULL, "must be at least one input to n");
  return deepb;
}


//------------------------------schedule_early---------------------------------
// Find the earliest Block any instruction can be placed in.  Some instructions
// are pinned into Blocks.  Unpinned instructions can appear in last block in 
// which all their inputs occur.
bool PhaseCFG::schedule_early(VectorSet &visited, Node_List &roots) {
  // Allocate stack with enough space to avoid frequent realloc
  Node_Stack nstack(roots.Size() + 8); // (unique >> 1) + 24 from Java2D stats
  // roots.push(_root); _root will be processed among C->top() inputs
  roots.push(C->top());
  visited.set(C->top()->_idx);

  while (roots.size() != 0) {
    // Use local variables nstack_top_n & nstack_top_i to cache values
    // on stack's top.
    Node *nstack_top_n = roots.pop();
    uint  nstack_top_i = 0;
//while_nstack_nonempty:
    while (true) {
      // Get parent node and next input's index from stack's top.
      Node *n = nstack_top_n;
      uint  i = nstack_top_i;

      if (i == 0) {
        // Special control input processing.
        // While I am here, go ahead and look for Nodes which are taking control
        // from a is_block_proj Node.  After I inserted RegionNodes to make proper
        // blocks, the control at a is_block_proj more properly comes from the
        // Region being controlled by the block_proj Node.  
        const Node *in0 = n->in(0);
        if (in0 != NULL) {              // Control-dependent?
          const Node *p = in0->is_block_proj();
          if (p != NULL && p != n) {    // Control from a block projection?
            // Find trailing Region
            Block *pb = _bbs[in0->_idx]; // Block-projection already has basic block
            uint j = 0;
            if (pb->_num_succs != 1) {  // More then 1 successor?
              // Search for successor
              uint max = pb->_nodes.size();
              assert( max > 1, "" );
              uint start = max - pb->_num_succs;
              // Find which output path belongs to projection
              for (j = start; j < max; j++) {
                if( pb->_nodes[j] == in0 )
                  break;
              }
              assert( j < max, "must find" );
              // Change control to match head of successor basic block
              j -= start;
            }
            n->set_req(0, pb->_succs[j]->head());
          }
        } else {               // n->in(0) == NULL
          if (n->req() == 1) { // This guy is a constant with NO inputs?
            n->set_req(0, _root);
          }
        }
      }

      // First, visit all inputs and force them to get a block.  If an
      // input is already in a block we quit following inputs (to avoid
      // cycles). Instead we put that Node on a worklist to be handled
      // later (since IT'S inputs may not have a block yet).
      bool done = true;              // Assume all n's inputs will be processed
      while (i < n->len()) {         // For all inputs
        Node *in = n->in(i);         // Get input
        ++i; 
        if (in == NULL) continue;    // Ignore NULL, missing inputs
        int is_visited = visited.test_set(in->_idx);
        if (!_bbs.lookup(in->_idx)) { // Missing block selection?
          if (is_visited) {
            // assert( !visited.test(in->_idx), "did not schedule early" );
            return false;
          }
          nstack.push(n, i);         // Save parent node and next input's index.
          nstack_top_n = in;         // Process current input now.
          nstack_top_i = 0;
          done = false;              // Not all n's inputs processed. 
          break; // continue while_nstack_nonempty;
        } else if (!is_visited) {    // Input not yet visited?
          roots.push(in);            // Visit this guy later, using worklist
        }
      }
      if (done) {
        // All of n's inputs have been processed, complete post-processing.

        // Some instructions are pinned into a block.  These include Region,
        // Phi, Start, Return, and other control-dependent instructions and
        // any projections which depend on them.
        if (!n->pinned()) {
          // Set earliest legal block.
          _bbs.map(n->_idx, find_deepest_input(n, _bbs));
        }

        if (nstack.is_empty()) {
          // Finished all nodes on stack. 
          // Process next node on the worklist 'roots'.
          break;
        }
        // Get saved parent node and next input's index. 
        nstack_top_n = nstack.node();
        nstack_top_i = nstack.index();
        nstack.pop();
      } //    if (done)
    }   // while (true)
  }     // while (roots.size() != 0)
  return true;
}

//------------------------------dom_lca----------------------------------------
// Find least common ancestor in dominator tree
// LCA is a current notion of LCA, to be raised above 'this'.
// As a convenient boundary condition, return 'this' if LCA is NULL.
// Find the LCA of those two nodes.
Block* Block::dom_lca(Block* LCA) {
  if (LCA == NULL || LCA == this)  return this;

  Block* anc = this;
  while (anc->_dom_depth > LCA->_dom_depth)
    anc = anc->_idom;           // Walk up till anc is as high as LCA

  while (LCA->_dom_depth > anc->_dom_depth)
    LCA = LCA->_idom;           // Walk up till LCA is as high as anc

  while (LCA != anc) {          // Walk both up till they are the same
    LCA = LCA->_idom;
    anc = anc->_idom;
  }

  return LCA;
}

//--------------------------raise_LCA_above_use--------------------------------
// We are placing a definition, and have been given a def->use edge.
// The definition must dominate the use, so move the LCA upward in the
// dominator tree to dominate the use.  If the use is a phi, adjust
// the LCA only with the phi input paths which actually use this def.
static Block* raise_LCA_above_use(Block* LCA, Node* use, Node* def, Block_Array &bbs) {
  Block* buse = bbs[use->_idx];
  if (buse == NULL)    return LCA;   // Unused killing Projs have no use block
  if (!use->is_Phi())  return buse->dom_lca(LCA);
  uint pmax = use->req();       // Number of Phi inputs
  // Why does not this loop just break after finding the matching input to
  // the Phi?  Well...it's like this.  I do not have true def-use/use-def
  // chains.  Means I cannot distinguish, from the def-use direction, which
  // of many use-defs lead from the same use to the same def.  That is, this
  // Phi might have several uses of the same def.  Each use appears in a 
  // different predecessor block.  But when I enter here, I cannot distinguish
  // which use-def edge I should find the predecessor block for.  So I find
  // them all.  Means I do a little extra work if a Phi uses the same value
  // more than once.
  for (uint j=1; j<pmax; j++) { // For all inputs
    if (use->in(j) == def) {    // Found matching input?
      Block* pred = bbs[buse->pred(j)->_idx];
      LCA = pred->dom_lca(LCA);
    }
  }
  return LCA;
}

//----------------------------raise_LCA_above_marks----------------------------
// Return a new LCA that dominates LCA and any of its marked predecessors.
// Search all my parents up to 'early' (exclusive), looking for predecessors
// which are marked with the given index.  Return the LCA (in the dom tree)
// of all marked blocks.  If there are none marked, return the original
// LCA.
static Block* raise_LCA_above_marks(Block* LCA, node_idx_t mark,
                                    Block* early, Block_Array &bbs) {
  Block_List worklist;
  worklist.push(LCA);
  while (worklist.size() > 0) {
    Block* mid = worklist.pop();
    if (mid == early)  continue;  // stop searching here

    // Test and set the visited bit.
    if (mid->raise_LCA_visited() == mark)  continue;  // already visited
    mid->set_raise_LCA_visited(mark);

    // Don't process the current LCA, otherwise the search may terminate early
    if (mid != LCA && mid->raise_LCA_mark() == mark) {
      // Raise the LCA.
      LCA = mid->dom_lca(LCA);
      if (LCA == early)  break;   // stop searching everywhere
      assert(early->dominates(LCA), "early is high enough");
      // Resume searching at that point, skipping intermediate levels.
      worklist.push(LCA);
    } else {
      // Keep searching through this block's predecessors.
      for (uint j = 1, jmax = mid->num_preds(); j < jmax; j++) {
        Block* mid_parent = bbs[ mid->pred(j)->_idx ];
        worklist.push(mid_parent);
      }
    }
  }
  return LCA;
}

//--------------------------memory_early_block--------------------------------
// This is a variation of find_deepest_input, the heart of schedule_early.
// Find the "early" block for a load, if we considered only memory and
// address inputs, that is, if other data inputs were ignored.
//
// Because a subset of edges are considered, the resulting block will 
// be earlier (at a shallower dom_depth) than the true schedule_early 
// point of the node. We compute this earlier block as a more permissive
// site for anti-dependency insertion, but only if subsume_loads is enabled.
static Block* memory_early_block(Node* load, Block* early, Block_Array &bbs) {
  Node* base;
  Node* index;
  Node* store = load->in(MemNode::Memory);
  load->as_Mach()->memory_inputs(base, index);
  
  assert(base != NodeSentinel && index != NodeSentinel,
         "unexpected base/index inputs");
  
  Node* mem_inputs[4];
  int mem_inputs_length = 0;
  if (base != NULL)  mem_inputs[mem_inputs_length++] = base;
  if (index != NULL) mem_inputs[mem_inputs_length++] = index;
  if (store != NULL) mem_inputs[mem_inputs_length++] = store;

  // In the comparision below, add one to account for the control input,
  // which may be null, but always takes up a spot in the in array.
  if (mem_inputs_length + 1 < (int) load->req()) {
    // This "load" has more inputs than just the memory, base and index inputs.
    // For purposes of checking anti-dependences, we need to start 
    // from the early block of only the address portion of the instruction, 
    // and ignore other blocks that may have factored into the wider 
    // schedule_early calculation.
    if (load->in(0) != NULL) mem_inputs[mem_inputs_length++] = load->in(0);

    Block* deepb           = NULL;        // Deepest block so far
    int    deepb_dom_depth = 0;
    for (int i = 0; i < mem_inputs_length; i++) {
      Block* inb = bbs[mem_inputs[i]->_idx];
      if (deepb_dom_depth < (int) inb->_dom_depth) {
        // The new inb must be dominated by the previous deepb.
        // The various inputs must be linearly ordered in the dom
        // tree, or else there will not be a unique deepest block.
        DEBUG_ONLY(assert_dom(deepb, inb, load, bbs));
        deepb = inb;                      // Save deepest block
        deepb_dom_depth = deepb->_dom_depth;
      }
    }
    early = deepb;
  }

  return early;
}

//--------------------------insert_anti_dependences---------------------------
// A load may need to witness memory that nearby stores can overwrite.
// For each nearby store, either insert an "anti-dependence" edge
// from the load to the store, or else move LCA upward to force the
// load to (eventually) be scheduled in a block above the store.
//
// Do not add edges to stores on distinct control-flow paths;
// only add edges to stores which might interfere.
//
// Return the (updated) LCA.  There will not be any possibly interfering
// store between the load's "early block" and the updated LCA.
// Any stores in the updated LCA will have new precedence edges
// back to the load.  The caller is expected to schedule the load
// in the LCA, in which case the precedence edges will make LCM
// preserve anti-dependences.  The caller may also hoist the load
// above the LCA, if it is not the early block.
Block* PhaseCFG::insert_anti_dependences(Block* LCA, Node* load, bool verify) {
  assert(load->needs_anti_dependence_check(), "must be a load of some sort");
  assert(LCA != NULL, "");
  DEBUG_ONLY(Block* LCA_orig = LCA);

  // Compute the alias index.  Loads and stores with different alias indices
  // do not need anti-dependence edges.
  uint load_alias_idx = C->get_alias_index(load->adr_type());
#ifdef ASSERT
  if (load_alias_idx == Compile::AliasIdxBot && C->AliasLevel() > 0 &&
      (PrintOpto || VerifyAliases ||
       PrintMiscellaneous && (WizardMode || Verbose))) {
    // Load nodes should not consume all of memory.
    // Reporting a bottom type indicates a bug in adlc.
    // If some particular type of node validly consumes all of memory,
    // sharpen the preceding "if" to exclude it, so we can catch bugs here.
    tty->print_cr("*** Possible Anti-Dependence Bug:  Load consumes all of memory.");
    load->dump(2);
    if (VerifyAliases)  assert(load_alias_idx != Compile::AliasIdxBot, "");
  }
#endif
  assert(load_alias_idx || (load->is_Mach() && load->as_Mach()->ideal_Opcode() == Op_StrComp), 
         "String compare is only known 'load' that does not conflict with any stores");

  if (!C->alias_type(load_alias_idx)->is_rewritable()) {
    // It is impossible to spoil this load by putting stores before it,
    // because we know that the stores will never update the value
    // which 'load' must witness.
    return LCA;
  }

  node_idx_t load_index = load->_idx;

  // Note the earliest legal placement of 'load', as determined by
  // by the unique point in the dom tree where all memory effects
  // and other inputs are first available.  (Computed by schedule_early.)
  // For normal loads, 'early' is the shallowest place (dom graph wise)
  // to look for anti-deps between this load and any store.
  Block* early = _bbs[load_index];

  // If we are subsuming loads, compute an "early" block that only considers
  // memory or address inputs. This block may be different than the 
  // schedule_early block in that it could be at an even shallower depth in the 
  // dominator tree, and allow for a broader discovery of anti-dependences.
  if (C->subsume_loads()) {
    early = memory_early_block(load, early, _bbs);
  }

  ResourceArea *area = Thread::current()->resource_area();
  Node_List worklist_mem(area);     // prior memory state to store
  Node_List worklist_store(area);   // possible-def to explore
  Node_List non_early_stores(area); // all relevant stores outside of early
  bool must_raise_LCA = false;
  DEBUG_ONLY(VectorSet should_not_repeat(area));

#ifdef TRACK_PHI_INPUTS
  // %%% This extra checking fails because MergeMem nodes are not GVNed.
  // Provide "phi_inputs" to check if every input to a PhiNode is from the 
  // original memory state.  This indicates a PhiNode for which should not
  // prevent the load from sinking.  For such a block, set_raise_LCA_mark
  // may be overly conservative.
  // Mechanism: count inputs seen for each Phi encountered in worklist_store.
  DEBUG_ONLY(GrowableArray<uint> phi_inputs(area, C->unique(),0,0));
#endif

  // 'load' uses some memory state; look for users of the same state.
  // Recurse through MergeMem nodes to the stores that use them.

  // Each of these stores is a possible definition of memory
  // that 'load' needs to use.  We need to force 'load'
  // to occur before each such store.  When the store is in
  // the same block as 'load', we insert an anti-dependence
  // edge load->store.

  // The relevant stores "nearby" the load consist of a tree rooted
  // at initial_mem, with internal nodes of type MergeMem.
  // Therefore, the branches visited by the worklist are of this form:
  //    initial_mem -> (MergeMem ->)* store
  // The anti-dependence constraints apply only to the fringe of this tree.

  Node* initial_mem = load->in(MemNode::Memory);
  worklist_store.push(initial_mem);
  worklist_mem.push(NULL);
  DEBUG_ONLY(should_not_repeat.test_set(initial_mem->_idx));
  while (worklist_store.size() > 0) {
    // Examine a nearby store to see if it might interfere with our load.
    Node* mem   = worklist_mem.pop();
    Node* store = worklist_store.pop();
    uint op = store->Opcode();

    // MergeMems do not directly have anti-deps.
    // Treat them as internal nodes in a forward tree of memory states,
    // the leaves of which are each a 'possible-def'.
    if (store == initial_mem    // root (exclusive) of tree we are searching
        || op == Op_MergeMem    // internal node of tree we are searching
        ) {
      mem = store;   // It's not a possibly interfering store.
      for (DUIterator_Fast imax, i = mem->fast_outs(imax); i < imax; i++) {
        store = mem->fast_out(i);
        if (store->is_MergeMem()) {
          // Be sure we don't get into combinatorial problems.
          // (Allow phis to be repeated; they can merge two relevant states.)
          uint i = worklist_store.size();
          for (; i > 0; i--) {
            if (worklist_store.at(i-1) == store)  break;
          }
          if (i > 0)  continue; // already on work list; do not repeat
          DEBUG_ONLY(int repeated = should_not_repeat.test_set(store->_idx));
          assert(!repeated, "do not walk merges twice");
        }
        worklist_mem.push(mem);
        worklist_store.push(store);
      }
      continue;
    }

    if (op == Op_MachProj || op == Op_Catch)   continue;
    if (store->needs_anti_dependence_check())  continue;  // not really a store

    // Compute the alias index.  Loads and stores with different alias
    // indices do not need anti-dependence edges.  Wide MemBar's are
    // anti-dependent on everything (except immutable memories).
    const TypePtr* adr_type = store->adr_type();
    if (!C->can_alias(adr_type, load_alias_idx))  continue;

    // Most slow-path runtime calls do NOT modify Java memory, but
    // they can block and so write Raw memory.
    if (store->is_Mach()) {
      MachNode* mstore = store->as_Mach();
      if (load_alias_idx != Compile::AliasIdxRaw) {
        // Check for call into the runtime using the Java calling
        // convention (and from there into a wrapper); it has no
        // _method.  Can't do this optimization for Native calls because
        // they CAN write to Java memory.
        if (mstore->ideal_Opcode() == Op_CallStaticJava) {
          assert(mstore->is_MachSafePoint(), "");
          MachSafePointNode* ms = (MachSafePointNode*) mstore;
          assert(ms->is_MachCallJava(), "");
          MachCallJavaNode* mcj = (MachCallJavaNode*) ms;
          if (mcj->_method == NULL) {
            // These runtime calls do not write to Java visible memory
            // (other than Raw) and so do not require anti-dependence edges.
            continue;
          }
        }
        // Same for SafePoints: they read/write Raw but only read otherwise.
        // This is basically a workaround for SafePoints only defining control
        // instead of control + memory.
        if (mstore->ideal_Opcode() == Op_SafePoint) 
          continue;
      } else {
        // Some raw memory, such as the load of "top" at an allocation, 
        // can be control dependent on the previous safepoint. See 
        // comments in GraphKit::allocate_heap() about control input.  
        // Inserting an anti-dep between such a safepoint and a use 
        // creates a cycle, and will cause a subsequent failure in 
        // local scheduling.  (BugId 4919904)
        // (%%% How can a control input be a safepoint and not a projection??)
        if (mstore->ideal_Opcode() == Op_SafePoint && load->in(0) == mstore)
          continue;
      }
    }

    // Identify a block that the current load must be above,
    // or else observe that 'store' is all the way up in the
    // earliest legal block for 'load'.  In the latter case,
    // immediately insert an anti-dependence edge.
    Block* store_block = _bbs[store->_idx];
    assert(store_block != NULL, "unused killing projections skipped above");

    if (store->is_Phi()) {
      // 'load' uses memory which is one (or more) of the Phi's inputs.
      // It must be scheduled not before the Phi, but rather before
      // each of the relevant Phi inputs.
      //
      // Instead of finding the LCA of all inputs to a Phi that match 'mem',
      // we mark each corresponding predecessor block and do a combined
      // hoisting operation later (raise_LCA_above_marks).
      //
      // Do not assert(store_block != early, "Phi merging memory after access")
      // PhiNode may be at start of block 'early' with backedge to 'early'
      DEBUG_ONLY(bool found_match = false);
      for (uint j = PhiNode::Input, jmax = store->req(); j < jmax; j++) {
        if (store->in(j) == mem) {   // Found matching input?
          DEBUG_ONLY(found_match = true);
          Block* pred_block = _bbs[store_block->pred(j)->_idx];
          if (pred_block != early) {
            // If any predecessor of the Phi matches the load's "early block",
            // we do not need a precedence edge between the Phi and 'load'
            // since the load will be forced into a block preceeding the Phi.
            pred_block->set_raise_LCA_mark(load_index);
            assert(!LCA_orig->dominates(pred_block) ||
                   early->dominates(pred_block), "early is high enough");
            must_raise_LCA = true;
          }
        }
      }
      assert(found_match, "no worklist bug");
#ifdef TRACK_PHI_INPUTS
#ifdef ASSERT
      // This assert asks about correct handling of PhiNodes, which may not 
      // have all input edges directly from 'mem'. See BugId 4621264
      int num_mem_inputs = phi_inputs.at_grow(store->_idx,0) + 1;
      // Increment by exactly one even if there are multiple copies of 'mem'
      // coming into the phi, because we will run this block several times
      // if there are several copies of 'mem'.  (That's how DU iterators work.)
      phi_inputs.at_put(store->_idx, num_mem_inputs);
      assert(PhiNode::Input + num_mem_inputs < store->req(),
             "Expect at least one phi input will not be from original memory state");
#endif //ASSERT
#endif //TRACK_PHI_INPUTS
    } else if (store_block != early) {
      // 'store' is between the current LCA and earliest possible block.
      // Label its block, and decide later on how to raise the LCA
      // to include the effect on LCA of this store.
      // If this store's block gets chosen as the raised LCA, we
      // will find him on the non_early_stores list and stick him
      // with a precedence edge.
      // (But, don't bother if LCA is already raised all the way.)
      if (LCA != early) {
        store_block->set_raise_LCA_mark(load_index);
        must_raise_LCA = true;
        non_early_stores.push(store);
      }
    } else {
      // Found a possibly-interfering store in the load's 'early' block.
      // This means 'load' cannot sink at all in the dominator tree.
      // Add an anti-dep edge, and squeeze 'load' into the highest block.
      assert(store != load->in(0), "dependence cycle found");
      if (verify) {
        assert(store->find_edge(load) != -1, "missing precedence edge");
      } else {
        store->add_prec(load);
      }
      LCA = early;
      // This turns off the process of gathering non_early_stores.
    }
  }
  // (Worklist is now empty; all nearby stores have been visited.)

  // Finished if 'load' must be scheduled in its 'early' block.
  // If we found any stores there, they have already been given
  // precedence edges.
  if (LCA == early)  return LCA;

  // We get here only if there are no possibly-interfering stores
  // in the load's 'early' block.  Move LCA up above all predecessors
  // which contain stores we have noted.
  //
  // The raised LCA block can be a home to such interfering stores,
  // but its predecessors must not contain any such stores.
  //
  // The raised LCA will be a lower bound for placing the load,
  // preventing the load from sinking past any block containing
  // a store that may invalidate the memory state required by 'load'.
  if (must_raise_LCA)
    LCA = raise_LCA_above_marks(LCA, load->_idx, early, _bbs);
  if (LCA == early)  return LCA;

  // Insert anti-dependence edges from 'load' to each store
  // in the non-early LCA block.
  // Mine the non_early_stores list for such stores.
  if (LCA->raise_LCA_mark() == load_index) {
    while (non_early_stores.size() > 0) {
      Node* store = non_early_stores.pop();
      Block* store_block = _bbs[store->_idx];
      if (store_block == LCA) {
        // add anti_dependence from store to load in its own block
        assert(store != load->in(0), "dependence cycle found");
        if (verify) {
          assert(store->find_edge(load) != -1, "missing precedence edge");
        } else {
          store->add_prec(load);
        }
      } else {
        assert(store_block->raise_LCA_mark() == load_index, "block was marked");
        // Any other stores we found must be either inside the new LCA
        // or else outside the original LCA.  In the latter case, they
        // did not interfere with any use of 'load'.
        assert(LCA->dominates(store_block)
               || !LCA_orig->dominates(store_block), "no stray stores");
      }
    }
  }

  // Return the highest block containing stores; any stores
  // within that block have been given anti-dependence edges.
  return LCA;
}

// This class is used to iterate backwards over the nodes in the graph.

class Node_Backward_Iterator {

private:
  Node_Backward_Iterator();

public:
  // Constructor for the iterator
  Node_Backward_Iterator(Node *root, VectorSet &visited, Node_List &stack, Block_Array &bbs);

  // Postincrement operator to iterate over the nodes
  Node *next();

private:
  VectorSet   &_visited;
  Node_List   &_stack;
  Block_Array &_bbs;
};

// Constructor for the Node_Backward_Iterator
Node_Backward_Iterator::Node_Backward_Iterator( Node *root, VectorSet &visited, Node_List &stack, Block_Array &bbs )
  : _visited(visited), _stack(stack), _bbs(bbs) {
  // The stack should contain exactly the root
  stack.clear();
  stack.push(root);

  // Clear the visited bits
  visited.Clear();
}

// Iterator for the Node_Backward_Iterator
Node *Node_Backward_Iterator::next() {

  // If the _stack is empty, then just return NULL: finished.
  if ( !_stack.size() )
    return NULL;

  // '_stack' is emulating a real _stack.  The 'visit-all-users' loop has been
  // made stateless, so I do not need to record the index 'i' on my _stack.
  // Instead I visit all users each time, scanning for unvisited users.
  // I visit unvisited not-anti-dependence users first, then anti-dependent
  // children next.
  Node *self = _stack.pop();

  // I cycle here when I am entering a deeper level of recursion.
  // The key variable 'self' was set prior to jumping here.
  while( 1 ) {

    _visited.set(self->_idx);
      
    // Now schedule all uses as late as possible.
    uint src           = self->is_Proj() ? self->in(0)->_idx : self->_idx;
    uint src_pre_order = _bbs[src]->_pre_order;
      
    // Schedule all nodes in a post-order visit
    Node *unvisited = NULL;  // Unvisited anti-dependent Node, if any

    // Scan for unvisited nodes
    for (DUIterator_Fast imax, i = self->fast_outs(imax); i < imax; i++) {
      // For all uses, schedule late
      Node* n = self->fast_out(i); // Use

      // Skip already visited children
      if ( _visited.test(n->_idx) )
        continue;

      // do not traverse backward control edges
      Node *use = n->is_Proj() ? n->in(0) : n;
      uint use_pre_order = _bbs[use->_idx]->_pre_order;

      if ( use_pre_order < src_pre_order )
        continue;

      // Phi nodes always precede uses in a basic block
      if ( use_pre_order == src_pre_order && use->is_Phi() )
        continue;

      unvisited = n;      // Found unvisited

      // Check for possible-anti-dependent 
      if( !n->needs_anti_dependence_check() ) 
        break;            // Not visited, not anti-dep; schedule it NOW
    }
      
    // Did I find an unvisited not-anti-dependent Node?
    if ( !unvisited ) 
      break;                  // All done with children; post-visit 'self'

    // Visit the unvisited Node.  Contains the obvious push to 
    // indicate I'm entering a deeper level of recursion.  I push the
    // old state onto the _stack and set a new state and loop (recurse).
    _stack.push(self);
    self = unvisited;
  } // End recursion loop

  return self;
}

//------------------------------ComputeLatenciesBackwards----------------------
// Compute the latency of all the instructions.
void PhaseCFG::ComputeLatenciesBackwards(VectorSet &visited, Node_List &stack) {
#ifndef PRODUCT
  if (trace_opto_pipelining())
    tty->print("\n#---- ComputeLatenciesBackwards ----\n");
#endif

  Node_Backward_Iterator iter((Node *)_root, visited, stack, _bbs);
  Node *n;

  // Walk over all the nodes from last to first
  while (n = iter.next()) {
    // Set the latency for the definitions of this instruction
    partial_latency_of_defs(n);
  }
} // end ComputeLatenciesBackwards

//------------------------------partial_latency_of_defs------------------------
// Compute the latency impact of this node on all defs.  This computes
// a number that increases as we approach the beginning of the routine.
void PhaseCFG::partial_latency_of_defs(Node *n) {
  // Set the latency for this instruction
#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("# latency_to_inputs: node_latency[%d] = %d for node",
               n->_idx, _node_latency.at_grow(n->_idx));
    dump();
  }
#endif

  if (n->is_Proj())
    n = n->in(0);

  if (n->is_Root())
    return;

  uint nlen = n->len();
  uint use_latency = _node_latency.at_grow(n->_idx);
  uint use_pre_order = _bbs[n->_idx]->_pre_order;

  for ( uint j=0; j<nlen; j++ ) {
    Node *def = n->in(j);

    if (!def || def == n)
      continue;
      
    // Walk backwards thru projections
    if (def->is_Proj())
      def = def->in(0);

#ifndef PRODUCT
    if (trace_opto_pipelining()) {
      tty->print("#    in(%2d): ", j);
      def->dump(); 
    }
#endif

    // If the defining block is not known, assume it is ok
    Block *def_block = _bbs[def->_idx];
    uint def_pre_order = def_block ? def_block->_pre_order : 0;

    if ( (use_pre_order <  def_pre_order) ||
         (use_pre_order == def_pre_order && n->is_Phi()) )
      continue;

    uint delta_latency = n->latency(j);
    uint current_latency = delta_latency + use_latency;

    if (_node_latency.at_grow(def->_idx) < current_latency) {
      _node_latency.at_put_grow(def->_idx, current_latency);
    }

#ifndef PRODUCT
    if (trace_opto_pipelining()) {
      tty->print_cr("#      %d + edge_latency(%d) == %d -> %d, node_latency[%d] = %d",
                    use_latency, j, delta_latency, current_latency, def->_idx, 
                    _node_latency.at_grow(def->_idx));
    }
#endif
  }
}

//------------------------------latency_from_use-------------------------------
// Compute the latency of a specific use
int PhaseCFG::latency_from_use(Node *n, const Node *def, Node *use) {
  // If self-reference, return no latency
  if (use == n || use->is_Root())
    return 0;
    
  uint def_pre_order = _bbs[def->_idx]->_pre_order;
  uint latency = 0;

  // If the use is not a projection, then it is simple...
  if (!use->is_Proj()) {
#ifndef PRODUCT
    if (trace_opto_pipelining()) {
      tty->print("#    out(): ");
      use->dump();
    }
#endif

    uint use_pre_order = _bbs[use->_idx]->_pre_order;

    if (use_pre_order < def_pre_order)
      return 0;

    if (use_pre_order == def_pre_order && use->is_Phi())
      return 0;

    uint nlen = use->len();
    uint nl = _node_latency.at_grow(use->_idx);

    for ( uint j=0; j<nlen; j++ ) {
      if (use->in(j) == n) {
        // Change this if we want local latencies
        uint ul = use->latency(j);
        uint  l = ul + nl;
        if (latency < l) latency = l;
#ifndef PRODUCT
        if (trace_opto_pipelining()) {
          tty->print_cr("#      %d + edge_latency(%d) == %d -> %d, latency = %d",
                        nl, j, ul, l, latency);
        }
#endif
      }
    }
  } else {
    // This is a projection, just grab the latency of the use(s)
    for (DUIterator_Fast jmax, j = use->fast_outs(jmax); j < jmax; j++) {
      uint l = latency_from_use(use, def, use->fast_out(j));
      if (latency < l) latency = l;
    }
  }

  return latency;
}

//------------------------------latency_from_uses------------------------------
// Compute the latency of this instruction relative to all of it's uses.
// This computes a number that increases as we approach the beginning of the
// routine.
void PhaseCFG::latency_from_uses(Node *n) {
  // Set the latency for this instruction
#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("# latency_from_outputs: node_latency[%d] = %d for node", 
               n->_idx, _node_latency.at_grow(n->_idx));
    dump();
  }
#endif
  uint latency=0;
  const Node *def = n->is_Proj() ? n->in(0): n;

  for (DUIterator_Fast imax, i = n->fast_outs(imax); i < imax; i++) {
    uint l = latency_from_use(n, def, n->fast_out(i));

    if (latency < l) latency = l;
  }

  _node_latency.at_put_grow(n->_idx, latency);
}

//------------------------------hoist_to_cheaper_block-------------------------
// Pick a block for node self, between early and LCA, that is a cheaper 
// alternative to LCA.
Block* PhaseCFG::hoist_to_cheaper_block(Block* LCA, Block* early, Node* self) {
  const double delta = 1+PROB_UNLIKELY_MAG(4);
  Block* least       = LCA;
  double least_freq  = least->_freq;
  uint target        = _node_latency.at_grow(self->_idx);
  uint start_latency = _node_latency.at_grow(LCA->_nodes[0]->_idx);
  uint end_latency   = _node_latency.at_grow(LCA->_nodes[LCA->end_idx()]->_idx);
  bool in_latency    = (target <= start_latency);
  const Block* root_block = _bbs[_root->_idx];

  // Turn off latency scheduling if scheduling is just plain off
  if (!C->do_scheduling())
    in_latency = true;

  // Do not hoist (to cover latency) instructions which target a
  // single register.  Hoisting stretches the live range of the
  // single register and may force spilling.
  MachNode* mach = self->is_Mach() ? self->as_Mach() : NULL;
  if (mach && mach->out_RegMask().is_bound1() && mach->out_RegMask().is_NotEmpty())
    in_latency = true;

#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("# Find cheaper block for latency %d: ",
      _node_latency.at_grow(self->_idx));
    self->dump();
    tty->print_cr("#   B%d: start latency for [%4d]=%d, end latency for [%4d]=%d, freq=%g",
      LCA->_pre_order,
      LCA->_nodes[0]->_idx,
      start_latency,
      LCA->_nodes[LCA->end_idx()]->_idx,
      end_latency,
      least_freq);
  }
#endif

  // Walk up the dominator tree from LCA (Lowest common ancestor) to
  // the earliest legal location.  Capture the least execution frequency.
  while (LCA != early) {
    LCA = LCA->_idom;         // Follow up the dominator tree

    if (LCA == NULL) {
      // Bailout without retry
      C->record_method_not_compilable("late schedule failed: LCA == NULL");
      return least;
    }

    // Don't hoist machine instructions to the root basic block
    if (mach && LCA == root_block)
      break;

    uint start_lat = _node_latency.at_grow(LCA->_nodes[0]->_idx);
    uint end_idx   = LCA->end_idx();
    uint end_lat   = _node_latency.at_grow(LCA->_nodes[end_idx]->_idx);
    double LCA_freq = LCA->_freq;
#ifndef PRODUCT
    if (trace_opto_pipelining()) {
      tty->print_cr("#   B%d: start latency for [%4d]=%d, end latency for [%4d]=%d, freq=%g",
        LCA->_pre_order, LCA->_nodes[0]->_idx, start_lat, end_idx, end_lat, LCA_freq);
    }
#endif
    if (LCA_freq < least_freq              || // Better Frequency
        ( !in_latency                   &&    // No block containing latency
          LCA_freq < least_freq * delta &&    // No worse frequency
          target >= end_lat             &&    // within latency range
          !self->is_iteratively_computed() )  // But don't hoist IV increments
             // because they may end up above other uses of their phi forcing
             // their result register to be different from their input.
       ) {
      least = LCA;            // Found cheaper block
      least_freq = LCA_freq;
      start_latency = start_lat;
      end_latency = end_lat;
      if (target <= start_lat)
        in_latency = true;
    }
  }

#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print_cr("#  Choose block B%d with start latency=%d and freq=%g",
      least->_pre_order, start_latency, least_freq);
  }
#endif

  // See if the latency needs to be updated
  if (target < end_latency) {
#ifndef PRODUCT
    if (trace_opto_pipelining()) {
      tty->print_cr("#  Change latency for [%4d] from %d to %d", self->_idx, target, end_latency);
    }
#endif
    _node_latency.at_put_grow(self->_idx, end_latency);
    partial_latency_of_defs(self);
  }

  return least;
}


//------------------------------schedule_late-----------------------------------
// Now schedule all codes as LATE as possible.  This is the LCA in the 
// dominator tree of all USES of a value.  Pick the block with the least
// loop nesting depth that is lowest in the dominator tree.
extern const char must_clone[];
void PhaseCFG::schedule_late(VectorSet &visited, Node_List &stack) {
#ifndef PRODUCT
  if (trace_opto_pipelining())
    tty->print("\n#---- schedule_late ----\n");
#endif

  Node_Backward_Iterator iter((Node *)_root, visited, stack, _bbs);
  Node *self;

  // Walk over all the nodes from last to first
  while (self = iter.next()) {
    Block* early = _bbs[self->_idx];   // Earliest legal placement

    if (self->is_top()) {
      // Top node goes in bb #2 with other constants.
      // It must be special-cased, because it has no out edges.
      early->add_inst(self);
      continue;
    }

    // No uses, just terminate
    if (self->outcnt() == 0) {
      assert(self->Opcode() == Op_MachProj, "sanity");
      continue;                   // Must be a dead machine projection
    }

    // If node is pinned in the block, then no scheduling can be done.
    if( self->pinned() )          // Pinned in block?
      continue;

    MachNode* mach = self->is_Mach() ? self->as_Mach() : NULL;
    if (mach) {
      switch (mach->ideal_Opcode()) {
      case Op_CreateEx:
        // Don't move exception creation
        early->add_inst(self);
        continue;
        break;
      case Op_CheckCastPP:
        // Don't move CheckCastPP nodes away from their input, if the input
        // is a rawptr (5071820).
        Node *def = self->in(1);
        if (def != NULL && def->bottom_type()->base() == Type::RawPtr) {
          early->add_inst(self);
          continue;
        }
        break;
      }
    }
    
    // Gather LCA of all uses
    Block *LCA = NULL;
    {
      for (DUIterator_Fast imax, i = self->fast_outs(imax); i < imax; i++) {
        // For all uses, find LCA
        Node* use = self->fast_out(i);
        LCA = raise_LCA_above_use(LCA, use, self, _bbs);
      }
    }  // (Hide defs of imax, i from rest of block.)

    // Place temps in the block of their use.  This isn't a
    // requirement for correctness but it reduces useless
    // interference between temps and other nodes.
    if (mach != NULL && mach->is_MachTemp()) {
      _bbs.map(self->_idx, LCA);
      LCA->add_inst(self);
      continue;
    }

    // Check if 'self' could be anti-dependent on memory
    if (self->needs_anti_dependence_check()) {
      // Hoist LCA above possible-defs and insert anti-dependences to
      // defs in new LCA block.
      LCA = insert_anti_dependences(LCA, self);
    }

    if (early->_dom_depth > LCA->_dom_depth) {
      // Somehow the LCA has moved above the earliest legal point.
      // (One way this can happen is via memory_early_block.)
      if (C->subsume_loads() == true && !C->failing()) {
        // Retry with subsume_loads == false
        // If this is the first failure, the sentinel string will "stick"
        // to the Compile object, and the C2Compiler will see it and retry.
        C->record_failure(C2Compiler::retry_no_subsuming_loads());
      } else {
        // Bailout without retry when (early->_dom_depth > LCA->_dom_depth)
        C->record_method_not_compilable("late schedule failed: incorrect graph");
      }
      return;
    }

    // If there is no opportunity to hoist, then we're done.
    bool try_to_hoist = (LCA != early);

    // Must clone guys stay next to use; no hoisting allowed.
    // Also cannot hoist guys that alter memory or are otherwise not
    // allocatable (hoisting can make a value live longer, leading to
    // anti and output dependency problems which are normally resolved
    // by the register allocator giving everyone a different register).
    if (mach != NULL && must_clone[mach->ideal_Opcode()])
      try_to_hoist = false;

    Block* late = NULL;
    if (try_to_hoist) {
      // Now find the block with the least execution frequency.
      // Start at the latest schedule and work up to the earliest schedule
      // in the dominator tree.  Thus the Node will dominate all its uses.
      late = hoist_to_cheaper_block(LCA, early, self);
    } else {
      // Just use the LCA of the uses.
      late = LCA;
    }

    // Put the node into target block
    schedule_node_into_block(self, late);
    
#ifdef ASSERT
    if (self->needs_anti_dependence_check()) {
      // since precedence edges are only inserted when we're sure they
      // are needed make sure that after placement in a block we don't
      // need any new precedence edges.
      verify_anti_dependences(late, self);
    }
#endif
  } // Loop until all nodes have been visited

} // end ScheduleLate

//------------------------------GlobalCodeMotion-------------------------------
void PhaseCFG::GlobalCodeMotion( Matcher &matcher, uint unique, Node_List &proj_list ) {
  ResourceMark rm;

#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("\n---- Start GlobalCodeMotion ----\n");
  }
#endif

  // Initialize the bbs.map for things on the proj_list
  uint i;
  for( i=0; i < proj_list.size(); i++ )
    _bbs.map(proj_list[i]->_idx, NULL);

  // Set the basic block for Nodes pinned into blocks
  Arena *a = Thread::current()->resource_area();
  VectorSet visited(a);
  schedule_pinned_nodes( visited );

  // Find the earliest Block any instruction can be placed in.  Some
  // instructions are pinned into Blocks.  Unpinned instructions can
  // appear in last block in which all their inputs occur.
  visited.Clear();
  Node_List stack(a);
  stack.map( (unique >> 1) + 16, NULL); // Pre-grow the list
  if (!schedule_early(visited, stack)) {
    // Bailout without retry
    C->record_method_not_compilable("early schedule failed");
    return;
  }

  // Build Def-Use edges.
  proj_list.push(_root);        // Add real root as another root
  proj_list.pop();

  // Compute the latency information (via backwards walk) for all the
  // instructions in the graph
  GrowableArray<uint> node_latency;
  _node_latency = node_latency;

  if( C->do_scheduling() )
    ComputeLatenciesBackwards(visited, stack); 

  // Now schedule all codes as LATE as possible.  This is the LCA in the 
  // dominator tree of all USES of a value.  Pick the block with the least
  // loop nesting depth that is lowest in the dominator tree.  
  // ( visited.Clear() called in schedule_late()->Node_Backward_Iterator() )
  schedule_late(visited, stack);
  if( C->failing() ) {
    // schedule_late fails only when graph is incorrect.
    assert(!VerifyGraphEdges, "verification should have failed");
    return;
  }

  unique = C->unique();

#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("\n---- Detect implicit null checks ----\n");
  }
#endif

  // Detect implicit-null-check opportunities.  Basically, find NULL checks 
  // with suitable memory ops nearby.  Use the memory op to do the NULL check.
  // I can generate a memory op if there is not one nearby.
  if (C->is_method_compilation()) {
    // Don't do it for natives, adapters, or runtime stubs
    int allowed_reasons = 0;
    // ...and don't do it when there have been too many traps, globally.
    for (int reason = (int)Deoptimization::Reason_none+1;
         reason < Compile::trapHistLength; reason++) {
      assert(reason < BitsPerInt, "recode bit map");
      if (!C->too_many_traps((Deoptimization::DeoptReason) reason))
        allowed_reasons |= nth_bit(reason);
    }
    // By reversing the loop direction we get a very minor gain on mpegaudio.
    // Feel free to revert to a forward loop for clarity.
    // for( int i=0; i < (int)matcher._null_check_tests.size(); i+=2 ) {
    for( int i= matcher._null_check_tests.size()-2; i>=0; i-=2 ) {
      Node *proj = matcher._null_check_tests[i  ];
      Node *val  = matcher._null_check_tests[i+1];
      _bbs[proj->_idx]->implicit_null_check(this, proj, val, allowed_reasons);
      // The implicit_null_check will only perform the transformation
      // if the null branch is truly uncommon, *and* it leads to an
      // uncommon trap.  Combined with the too_many_traps guards
      // above, this prevents SEGV storms reported in 6366351,
      // by recompiling offending methods without this optimization.
    }
  }

#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("\n---- Start Local Scheduling ----\n");
  }
#endif

  // Schedule locally.  Right now a simple topological sort.
  // Later, do a real latency aware scheduler.
  int *ready_cnt = NEW_RESOURCE_ARRAY(int,C->unique());
  memset( ready_cnt, -1, C->unique() * sizeof(int) );
  visited.Clear();
  for (i = 0; i < _num_blocks; i++) {
    if (!_blocks[i]->schedule_local(this, matcher, ready_cnt, visited)) {
      if (!C->failure_reason_is(C2Compiler::retry_no_subsuming_loads())) {
        C->record_method_not_compilable("local schedule failed");
      }
      return;
    }
  }

  // If we inserted any instructions between a Call and his CatchNode,
  // clone the instructions on all paths below the Catch.
  for( i=0; i < _num_blocks; i++ )
    _blocks[i]->call_catch_cleanup(_bbs);

#ifndef PRODUCT
  if (trace_opto_pipelining()) {
    tty->print("\n---- After GlobalCodeMotion ----\n");
    for (uint i = 0; i < _num_blocks; i++) {
      _blocks[i]->dump();
    }
  }
#endif
}

#define MAXFREQ BLOCK_FREQUENCY(1e35f)
#define MINFREQ BLOCK_FREQUENCY(1e-35f)

//------------------------------Estimate_Block_Frequency-----------------------
// Estimate block frequencies based on IfNode probabilities.
// Two pass algorithm does a forward propagation in the first pass with some
// correction factors where static predictions are needed.  Then, the second
// pass pushes through changes caused by back edges.  This will give "exact"
// results for all dynamic frequencies, and for all staticly predicted code
// with loop nesting depth of one or less.  Static predictions with greater
// than nesting depth of one are already subject to so many static fudge
// factors that it is not worth iterating to a fixed point.
void PhaseCFG::Estimate_Block_Frequency() {
  assert( _blocks[0] == _broot, "" );
  int cnts = C->method() ? C->method()->interpreter_invocation_count() : 1;
  // Most of our algorithms will die horribly if frequency can become
  // negative so make sure cnts is a sane value.
  if( cnts <= 0 ) cnts = 1;
  float f = (float)cnts/(float)FreqCountInvocations;
  _broot->_freq = f;
  _broot->_cnt  = f;
  // Do a two pass propagation of frequency information
  // PASS 1: Walk the blocks in RPO, propagating frequency info
  uint i;
  for( i = 0; i < _num_blocks; i++ ) {
    Block *b = _blocks[i];

    // Make any necessary modifications to b's frequency
    int hop = b->head()->Opcode();
    // On first trip, scale loop heads by 10 if no counts are available
    if( (hop == Op_Loop || hop == Op_CountedLoop) &&
        (b->_cnt == COUNT_UNKNOWN) && (b->_freq < MAXFREQ) ) {
      // Try to figure out how much to scale the loop by; look for a
      // gating loop-exit test with "reasonable" back-branch
      // frequency.

      // Try and find a real loop-back controlling edge and use that
      // frequency. If we can't find it, use the old default of 10
      // otherwise use the new value. This helps loops with low
      // frequency (like allocation contention loops with -UseTLE).
      // Note special treatment below of LoopNode::EntryControl edges.      
      Block *loopprior = b;          
      Block *loopback = _bbs[b->pred(LoopNode::LoopBackControl)->_idx];
      // See if this block ends in a test (probably not) or just a
      // goto the loop head.
      if( loopback->_num_succs == 1 &&
          loopback->num_preds() == 2 ) {
        loopprior = loopback;
        // NOTE: constant 1 here isn't magic, it's just that there's exactly 1
        // predecessor (checked just above) and predecessors are 1-based, so
        // the "1" refers to the first (and only) predecessor.
        loopback = _bbs[loopprior->pred(1)->_idx];
      }
      // Call the edge frequency leading from loopback to loopprior f.
      // Then scale the loop by 1/(1-f).  Thus a loop-back edge
      // frequency of 0.9 leads to a scale factor of 10.
      float f = 0.9f;           // Default scale factor

      if( loopback->_num_succs == 2 ) {
        int eidx = loopback->end_idx();
        Node *mn = loopback->_nodes[eidx]; // Get ending Node
        if( mn->is_MachIf() ) {
          // MachIfNode has branch probability info
          f = mn->as_MachIf()->_prob;
          int taken = (loopback->_succs[1] == loopprior);
          assert( loopback->_succs[taken] == loopprior, "" );
          if( loopback->_nodes[eidx+1+taken]->Opcode() == Op_IfFalse ) 
            f = 1-f;              // Inverted branch sense
          if( f > 0.99f )         // Limit scale to 100
            f = 0.99f;
        }
      }
      
      // Scale loop head by this much
      b->_freq *= 1/(1-f);
      assert(b->_freq > 0.0f,"Bad frequency assignment");
    }

    // Push b's frequency to successors
    int eidx = b->end_idx();    
    Node *n = b->_nodes[eidx];  // Get ending Node
    int op = n->is_Mach() ? n->as_Mach()->ideal_Opcode() : n->Opcode();
    // Switch on branch type
    switch( op ) {
    // Conditionals pass on only part of their frequency and count
    case Op_CountedLoopEnd:
    case Op_If: {
      int taken  = 0;  // this is the index of the TAKEN path
      int ntaken = 1;  // this is the index of the NOT TAKEN path
      // If succ[0] is the FALSE branch, invert path info
      if( b->_nodes[eidx+1]->Opcode() == Op_IfFalse ) {
        taken  = 1;
        ntaken = 0;
      }
      float prob  = n->as_MachIf()->_prob;
      float nprob = 1.0f - prob;
      float cnt   = n->as_MachIf()->_fcnt;
      // If branch frequency info is available, use it
      if(cnt != COUNT_UNKNOWN) {
        float tcnt = b->_succs[taken]->_cnt;
        float ncnt = b->_succs[ntaken]->_cnt;
        // Taken Branch
        b->_succs[taken]->_freq += prob * cnt;
        b->_succs[taken]->_cnt = (tcnt == COUNT_UNKNOWN) ? (prob * cnt) : tcnt + (prob * cnt);
        // Not Taken Branch
        b->_succs[ntaken]->_freq += nprob * cnt;
        b->_succs[ntaken]->_cnt = (ncnt == COUNT_UNKNOWN) ? (nprob * cnt) : ncnt + (nprob * cnt);
      }
      // Otherwise, split frequency amongst children
      else {
        b->_succs[taken]->_freq  +=  prob * b->_freq;
        b->_succs[ntaken]->_freq += nprob * b->_freq;
      }
      // Special case for underflow caused by infrequent branches
      if(b->_succs[taken]->_freq < MINFREQ) b->_succs[taken]->_freq = MINFREQ;
      if(b->_succs[ntaken]->_freq < MINFREQ) b->_succs[ntaken]->_freq = MINFREQ;
      assert(b->_succs[0]->has_valid_counts(),"Bad frequency/count");
      assert(b->_succs[1]->has_valid_counts(),"Bad frequency/count");
      break;
    }
    case Op_NeverBranch:  {
      b->_succs[0]->_freq += b->_freq;
      // Special case for underflow caused by infrequent branches
      if(b->_succs[0]->_freq < MINFREQ) b->_succs[0]->_freq = MINFREQ;
      if(b->_succs[1]->_freq < MINFREQ) b->_succs[1]->_freq = MINFREQ;
      break;
    }
      // Split frequency amongst children
    case Op_Jump: {
      // Divide the frequency between all successors evenly
      float predfreq = b->_freq/b->_num_succs;
      float predcnt = COUNT_UNKNOWN;
      for (uint j = 0; j < b->_num_succs; j++) {
        b->_succs[j]->_freq += predfreq;
        if (b->_succs[j]->_freq < MINFREQ) {
          b->_succs[j]->_freq = MINFREQ;
        }
        assert(b->_succs[j]->has_valid_counts(), "Bad frequency/count");
      }
      break;
    }      
      // Split frequency amongst children
    case Op_Catch: {
      // Fall-thru path gets the lion's share.
      float fall = (1.0f - PROB_UNLIKELY_MAG(5)*b->_num_succs)*b->_freq;
      // Exception exits are uncommon.
      float expt = PROB_UNLIKELY_MAG(5) * b->_freq;
      // Iterate over children pushing out frequency
      for( uint j = 0; j < b->_num_succs; j++ ) {
        const CatchProjNode *x = b->_nodes[eidx+1+j]->as_CatchProj();
        b->_succs[j]->_freq += 
          ((x->_con == CatchProjNode::fall_through_index) ? fall : expt);
        // Special case for underflow caused by nested catches
        if(b->_succs[j]->_freq < MINFREQ) b->_succs[j]->_freq = MINFREQ;
        assert(b->_succs[j]->has_valid_counts(), "Bad Catch frequency/count assignment");
      }
      break;
    }
    // Pass frequency straight thru to target
    case Op_Root:
    case Op_Goto: {
      Block *bs = b->_succs[0];
      int hop = bs->head()->Opcode();
      bool notloop = (hop != Op_Loop && hop != Op_CountedLoop);
      // Pass count straight thru to target (except for loops)
      if( notloop && b->_cnt != COUNT_UNKNOWN ) {
        if( bs->_cnt == COUNT_UNKNOWN )
          bs->_cnt = 0;
        bs->_cnt += b->_cnt;
      }
      // Loops and counted loops have already had their heads scaled
      // by an amount which accounts for the backedge (but not their
      // entry).  Add frequency for normal blocks and loop entries.
      // Note special treatment above of LoopNode::LoopBackControl edges.
      if( notloop || bs->_freq <= 0 /*this is needed for irreducible loops*/||
          _bbs[bs->pred(LoopNode::EntryControl)->_idx] == b )
        bs->_freq += b->_freq;

      assert(bs->has_valid_counts(), "Bad goto frequency/count assignment");
      break;
    }
    // Do not push out freq to root block
    case Op_TailCall:
    case Op_TailJump:
    case Op_Return:
    case Op_Halt:
    case Op_Rethrow:
      break;
    default: 
      ShouldNotReachHere();
    } // End switch(op)
    assert(b->has_valid_counts(), "Bad first pass frequency/count");
  } // End for all blocks


  // PASS 2: Fix up loop bodies
  for( i = 1; i < _num_blocks; i++ ) {
    Block *b = _blocks[i];
    float freq = 0.0f;
    float cnt  = COUNT_UNKNOWN;
    // If it ends in a Halt or call marked uncommon, assume the block is uncommon.
    Node* be = b->end();
    if (be->is_Goto())
      be = be->in(0);
    if (be->is_Catch())
      be = be->in(0);
    if (be->is_Proj() && be->in(0)->is_MachCall()) {
      MachCallNode* call = be->in(0)->as_MachCall();
      if (call->cnt() != COUNT_UNKNOWN && call->cnt() <= PROB_UNLIKELY_MAG(4)) {
        // This is true for slow-path stubs like new_{instance,array},
        // slow_arraycopy, complete_monitor_locking, uncommon_trap.
        // The magic number corresponds to the probability of an uncommon_trap,
        // even though it is a count not a probability.
        if (b->_freq > BLOCK_FREQUENCY(1e-6))
          b->_freq = BLOCK_FREQUENCY(1e-6f);
        continue;
      }
    }
    if (be->is_Mach() && be->as_Mach()->ideal_Opcode() == Op_Halt) {
      if( b->_freq > BLOCK_FREQUENCY(1e-6) )
        b->_freq = BLOCK_FREQUENCY(1e-6f);
      continue;
    }

    // Recompute frequency based upon predecessors' frequencies
    for(uint j = 1; j < b->num_preds(); j++) {
      // Compute the frequency passed along this path
      Node *pred = b->head()->in(j);
      // Peek through projections
      if(pred->is_Proj()) pred = pred->in(0);
      // Grab the predecessor block's frequency
      Block *pblock = _bbs[pred->_idx];
      float predfreq = pblock->_freq;
      float predcnt = pblock->_cnt;
      // Properly modify the frequency for this exit path
      int op = pred->is_Mach() ? pred->as_Mach()->ideal_Opcode() : pred->Opcode();
      // Switch on branch type
      switch(op) {
      // Conditionals pass on only part of their frequency and count
      case Op_CountedLoopEnd:
      case Op_If: {
        float prob = pred->as_MachIf()->_prob;
        float cnt  = pred->as_MachIf()->_fcnt;
        bool path  = true;
        // Is this the TRUE branch or the FALSE branch?
        if( b->head()->in(j)->Opcode() == Op_IfFalse )
          path = false;
        // If branch frequency info is available, use it
        if(cnt != COUNT_UNKNOWN) {
          predfreq = (path) ? (prob * cnt) : ((1.0f-prob) * cnt);
          predcnt  = (path) ? (prob * cnt) : ((1.0f-prob) * cnt);
        }
        // Otherwise, split frequency amongst children
        else {
          predfreq = (path) ? (prob * predfreq) : ((1.0f-prob) * predfreq);
          predcnt  = COUNT_UNKNOWN;
        }
        if( predfreq < MINFREQ ) predfreq = MINFREQ;

        // Raise frequency of the loop backedge block, in an effort
        // to keep it empty.  Must raise it by 10%+ because counted
        // loops normally keep a 90/10 exit ratio.
        if( op == Op_CountedLoopEnd && b->num_preds() == 2 && path == true )
          predfreq *= 1.15f;
        break;
      }
        // Catch splits frequency amongst multiple children
      case Op_Jump: {
        // Divide the frequency between all successors evenly
        predfreq = predfreq / pblock->_num_succs;
        predcnt = COUNT_UNKNOWN;
        if (predfreq < MINFREQ) predfreq = MINFREQ;
        break;
      }
      // Catch splits frequency amongst multiple children, favoring
      // fall through
      case Op_Catch: {
        // Fall-thru path gets the lion's share.
        float fall  = (1.0f - PROB_UNLIKELY_MAG(5)*pblock->_num_succs)*predfreq;
        // Exception exits are uncommon.
        float expt  = PROB_UNLIKELY_MAG(5) * predfreq;
        // Determine if this is fall-thru path
        const CatchProjNode *x = b->head()->in(j)->as_CatchProj();
        predfreq = (x->_con == CatchProjNode::fall_through_index) ? fall :expt;
        predcnt  = COUNT_UNKNOWN;
        if(predfreq < MINFREQ) predfreq = MINFREQ;
        break;
      }
      // Pass frequency straight thru to target
      case Op_Root:
      case Op_Goto:
      case Op_Start:
      case Op_NeverBranch:
        break;
      // These do not push out a frequency or count
      case Op_TailCall:
      case Op_TailJump:
      case Op_Return:
      case Op_Halt:
      case Op_Rethrow:
        predfreq = 0.0f;
        predcnt = COUNT_UNKNOWN;
        break;
      default: 
        ShouldNotReachHere();
      } // End switch(op)
      assert(predfreq > 0.0f,"Bad intermediate frequency");
      assert((predcnt > 0.0f) || (predcnt == COUNT_UNKNOWN),"Bad intermediate count");
      // Accumulate frequency from predecessor block
      freq += predfreq;
      if (predcnt != COUNT_UNKNOWN) {
        cnt = (cnt == COUNT_UNKNOWN) ? predcnt : cnt + predcnt;
      }
    }
    // Assign new frequency
    b->_freq = freq;
    b->_cnt = cnt;
    assert(b->has_valid_counts(), "Bad final frequency/count assignment");
  } // End for all blocks
}
