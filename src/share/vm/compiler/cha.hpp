#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "%W% %E% %U% JVM"
#endif
/*
 * Copyright 1997-1998 Sun Microsystems, Inc.  All Rights Reserved.
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

// Class Hierarchy Analysis 
// Computes the set of overriding methods for a particular call,
// using the subclass links in instanceKlass.
// Right now the CHA just traverses these links for every query;
// if this should become too slow we can put in a cache.

// result of a CHA query
class CHAResult : public ResourceObj {
  friend class CHA;
  const KlassHandle  _receiver;                                 // copies of the lookup (for better debugging)
  const symbolHandle _name;
  const symbolHandle _signature;
  const methodHandle _target;                                   // target method (if final)
  const bool         _valid;
  const GrowableArray<methodHandle>* const _target_methods;     // list of possible targets (NULL for final methods or if !UseCHA)
  const GrowableArray<KlassHandle>* const  _receivers;          // list of possible receiver klasses (NULL for final methods or if !UseCHA)

  CHAResult(KlassHandle receiver, symbolHandle name, symbolHandle signature,
            GrowableArray<KlassHandle>* receivers, GrowableArray<methodHandle>* methods, 
            methodHandle target, bool valid = true);
 public:
  KlassHandle  receiver() const                               { return _receiver; }
  symbolHandle name() const                                   { return _name; }
  symbolHandle signature() const                              { return _signature; }
  bool      is_accurate() const                               { return !_target_methods->is_full(); }
  bool      is_monomorphic() const;
  methodHandle monomorphic_target() const;                    // returns the single target (if is_monomorphic)
  KlassHandle  monomorphic_receiver() const;                  // receiver klass of monomorphic_target
  const GrowableArray<KlassHandle>*  receivers() const        { return _receivers; }
    // Returns the list of all subclasses that are possible receivers (empty array if none, capped at max_result).
    // The static receiver klass *is* included in the result (unless it is abstract).
    // The list is a class hierarchy preorder, i.e., subclasses precede their superclass.
    // All possible receiver classes are included, not just those that (re)define the method.
    // Abstract classes are suppressed.
  const GrowableArray<methodHandle>* target_methods() const   { return _target_methods; }
    // Returns the list of possible target methods, i.e., all methods potentially invoked
    // by this send (empty array if none, capped at max_result).
    // If the receiver klass (or one of its superclasses) defines the method, this definition 
    // is included in the result.  Abstract methods are suppressed.
  void print();
};


class CHA : AllStatic {
  static int _max_result;           // maximum result size (for efficiency)
  static bool _used;                // has CHA been used yet?  (will go away when deoptimization implemented)

  static void process_class(KlassHandle r, GrowableArray<KlassHandle>* receivers, GrowableArray<methodHandle>* methods, 
                            symbolHandle name, symbolHandle signature);
  static void process_interface(instanceKlassHandle r, GrowableArray<KlassHandle>* receivers, GrowableArray<methodHandle>* methods, 
                            symbolHandle name, symbolHandle signature);
 public:
  static bool has_been_used()       { return _used; }
  static int  max_result()          { return _max_result; }
  static void set_max_result(int n) { _max_result = n; }

  static CHAResult* analyze_call(KlassHandle calling_klass, KlassHandle static_receiver, 
                                 KlassHandle actual_receiver, symbolHandle name, symbolHandle signature);
};


