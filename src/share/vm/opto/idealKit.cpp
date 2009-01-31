#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)idealKit.cpp	1.7 07/05/05 17:06:29 JVM"
#endif
/*
 * Copyright 2005-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

#include "incls/_precompiled.incl"
#include "incls/_idealKit.cpp.incl"

// Static initialization
const uint IdealKit::first_var = 1;

//----------------------------IdealKit-----------------------------------------
IdealKit::IdealKit(PhaseGVN &gvn, Node* control, bool delay_all_transforms) :
  _gvn(gvn), C(gvn.C) {
  _initial_ctrl = control;
  _delay_all_transforms = delay_all_transforms;
  _var_ct = 0;
  _cvstate = NULL;
  int init_size = 5;
  _pending_cvstates = new (C->node_arena()) GrowableArray<Node*>(C->node_arena(), init_size, 0, 0);
  _delay_transform  = new (C->node_arena()) GrowableArray<Node*>(C->node_arena(), init_size, 0, 0);
  DEBUG_ONLY(_state = new (C->node_arena()) GrowableArray<int>(C->node_arena(), init_size, 0, 0));
}

//-------------------------------if_then-------------------------------------
// Create:  if(left relop right)
//          /  \
//   iffalse    iftrue
// Push the iffalse cvstate onto the stack. The iftrue becomes the current cvstate.
void IdealKit::if_then(Node* left, BoolTest::mask relop,
                       Node* right, float prob, float cnt, bool push_new_state) {
  assert((state() & (BlockS|LoopS|IfThenS|ElseS)), "bad state for new If");
  Node* bol = Bool(CmpI(left, right), relop);
  // Delay gvn.tranform on if-nodes until construction is finished
  // to prevent a constant bool input from discarding a control output.
  IfNode* iff = delay_transform(new (C, 2) IfNode(ctrl(), bol, prob, cnt))->as_If();
  Node* then  = IfTrue(iff);
  Node* elsen = IfFalse(iff);
  Node* else_cvstate = copy_cvstate();
  else_cvstate->set_req(0, elsen);
  _pending_cvstates->push(else_cvstate);
  DEBUG_ONLY(if (push_new_state) _state->push(IfThenS));
  set_ctrl(then);
}

//-------------------------------else_-------------------------------------
// Pop the else cvstate off the stack, and push the (current) then cvstate.
// The else cvstate becomes the current cvstate.
void IdealKit::else_() {
  assert(state() == IfThenS, "bad state for new Else");
  Node* else_cvstate = _pending_cvstates->pop();
  DEBUG_ONLY(_state->pop());
  // save current (then) cvstate for later use at endif
  _pending_cvstates->push(_cvstate);
  DEBUG_ONLY(_state->push(ElseS));
  _cvstate = else_cvstate;
}

//-------------------------------end_if-------------------------------------
// Merge the "then" and "else" cvstates from an "if" via:
// create label, generate a goto from the current cvstate to the
// new label, pop the other cvstate from the if ("else" cvstate if
// no else_() and "then" cvstate if there was), and bind the label
// to the popped cvstate.
void IdealKit::end_if() {
  assert(state() & (IfThenS|ElseS), "bad state for new Endif");
  Node* lab = make_label(1);
  goto_(lab);
  _cvstate = _pending_cvstates->pop();
  bind(lab);
  DEBUG_ONLY(_state->pop());
}

//-------------------------------loop-------------------------------------
// Create the loop head portion (*) of:
//  *     iv = init
//  *  top: (region node)
//  *     if (iv relop limit) {
//           loop body
//           i = i + 1
//           goto top
//  *     } else // exits loop
// 
// Pushes the loop top cvstate first, then the else (loop exit) cvstate
// onto the stack.
void IdealKit::loop(IdealVariable& iv, Node* init, BoolTest::mask relop, Node* limit, float prob, float cnt) {
  assert((state() & (BlockS|LoopS|IfThenS|ElseS)), "bad state for new loop");
  set(iv, init);
  Node* head = make_label(1);
  bind(head);
  _pending_cvstates->push(head); // push for use at end_loop
  _cvstate = copy_cvstate();
  if_then(value(iv), relop, limit, prob, cnt, false /* no new state */);
  DEBUG_ONLY(_state->push(LoopS));
  assert(ctrl()->is_IfTrue(), "true branch stays in loop");
  assert(_pending_cvstates->top()->in(0)->is_IfFalse(), "false branch exits loop");
}

//-------------------------------end_loop-------------------------------------
// Creates the goto top label.
// Expects the else (loop exit) cvstate to be on top of the
// stack, and the loop top cvstate to be 2nd.
void IdealKit::end_loop() {
  assert((state() == LoopS), "bad state for new end_loop");
  Node* exit = _pending_cvstates->pop();
  Node* head = _pending_cvstates->pop();
  goto_(head);
  clear(head);
  DEBUG_ONLY(_state->pop());
  _cvstate = exit;
}

//-------------------------------make_label-------------------------------------
// Creates a label.  The number of goto's
// must be specified (which should be 1 less than
// the number of precedessors.)
Node* IdealKit::make_label(int goto_ct) {
  assert(_cvstate != NULL, "must declare variables before labels");
  Node* lab = new_cvstate();
  int sz = 1 + goto_ct + 1 /* fall thru */;
  Node* reg = delay_transform(new (C, sz) RegionNode(sz));
  lab->init_req(0, reg);
  return lab;
}

//-------------------------------bind-------------------------------------
// Bind a label at the current cvstate by simulating
// a goto to the label.
void IdealKit::bind(Node* lab) {
  goto_(lab, true /* bind */);
  _cvstate = lab;
}

//-------------------------------goto_-------------------------------------
// Make the current cvstate a predecessor of the label,
// creating phi's to merge values.  If bind is true and
// this is not the last control edge, then ensure that
// all live values have phis created. Used to create phis
// at loop-top regions.
void IdealKit::goto_(Node* lab, bool bind) {
  Node* reg = lab->in(0);
  // find next empty slot in region
  uint slot = 1;
  while (slot < reg->req() && reg->in(slot) != NULL) slot++;
  assert(slot < reg->req(), "too many gotos");
  // If this is last predecessor, then don't force phi creation
  if (slot == reg->req() - 1) bind = false;
  reg->init_req(slot, ctrl());
  assert(first_var + _var_ct == _cvstate->req(), "bad _cvstate size");
  for (uint i = first_var; i < _cvstate->req(); i++) {
    Node* l = lab->in(i);
    Node* m = _cvstate->in(i);
    if (m == NULL) {
      continue;
    } else if (l == NULL || m == l) {
      if (bind) {
        m = promote_to_phi(m, reg);
      }
      lab->set_req(i, m);
    } else {
      if (!was_promoted_to_phi(l, reg)) {
        l = promote_to_phi(l, reg);
        lab->set_req(i, l);
      }
      l->set_req(slot, m);
    }
  }
  stop();
}

//-----------------------------promote_to_phi-----------------------------------
Node* IdealKit::promote_to_phi(Node* n, Node* reg) {
  assert(!was_promoted_to_phi(n, reg), "n already promoted to phi on this region");
  // Get a conservative type for the phi
  const BasicType bt = n->bottom_type()->basic_type();
  const Type* ct = Type::get_const_basic_type(bt);
  return delay_transform(PhiNode::make(reg, n, ct));
}

//-----------------------------declares_done-----------------------------------
void IdealKit::declares_done() {
  _cvstate = new_cvstate();   // initialize current cvstate
  set_ctrl(_initial_ctrl);    // initialize control in current cvstate
  DEBUG_ONLY(_state->push(BlockS));
}

//-----------------------------transform-----------------------------------
Node* IdealKit::transform(Node* n) {
  if (_delay_all_transforms) {
    return delay_transform(n);
  } else {
    return gvn().transform(n);
  }
}

//-----------------------------delay_transform-----------------------------------
Node* IdealKit::delay_transform(Node* n) {
  gvn().set_type(n, n->bottom_type());
  _delay_transform->push(n);
  return n;
}

//-----------------------------new_cvstate-----------------------------------
Node* IdealKit::new_cvstate() {
  uint sz = _var_ct + first_var;
  return new (C, sz) Node(sz);
}

//-----------------------------copy_cvstate-----------------------------------
Node* IdealKit::copy_cvstate() {
  Node* ns = new_cvstate();
  for (uint i = 0; i < ns->req(); i++) ns->init_req(i, _cvstate->in(i));
  return ns;
}

//-----------------------------clear-----------------------------------
void IdealKit::clear(Node* m) {
  for (uint i = 0; i < m->req(); i++) m->set_req(i, NULL);
}

//-----------------------------drain_delay_transform----------------------------
void IdealKit::drain_delay_transform() {
  while (_delay_transform->length() > 0) {
    Node* n = _delay_transform->pop();
    gvn().transform(n);
    if (!gvn().is_IterGVN()) {
      C->record_for_igvn(n);
    }
  }
}

//-----------------------------IdealVariable----------------------------
IdealVariable::IdealVariable(IdealKit &k) {
  k.declare(this);
}

