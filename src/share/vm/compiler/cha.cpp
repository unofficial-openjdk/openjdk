#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)cha.cpp	1.54 07/05/05 17:05:23 JVM"
#endif
/*
 * Copyright 1997-2005 Sun Microsystems, Inc.  All Rights Reserved.
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
#include "incls/_cha.cpp.incl"

bool CHA::_used = false;
int CHA::_max_result = 5;


CHAResult* CHA::analyze_call(KlassHandle calling_klass, KlassHandle static_receiver, KlassHandle actual_receiver, 
                             symbolHandle name, symbolHandle signature) {
  assert(static_receiver->oop_is_instance(), "must be instance klass");
  
  methodHandle m;
  // Only do exact lookup if receiver klass has been linked.  Otherwise,
  // the vtables has not been setup, and the LinkResolver will fail.
  if (instanceKlass::cast(static_receiver())->is_linked() && instanceKlass::cast(actual_receiver())->is_linked()) {    
    if (static_receiver->is_interface()) {
      // no point trying to resolve unless actual receiver is a klass
      if (!actual_receiver->is_interface()) {
        m = LinkResolver::resolve_interface_call_or_null(actual_receiver, static_receiver, name, signature, calling_klass);
      }
    } else {
      m = LinkResolver::resolve_virtual_call_or_null(actual_receiver, static_receiver, name, signature, calling_klass);
    }

    if (m.is_null()) {
      // didn't find method (e.g., could be abstract method)
      return new CHAResult(actual_receiver, name, signature, NULL, NULL, m, false);
    } 
    if( m()->can_be_statically_bound() ||
        m()->is_private() || 
        actual_receiver->subklass() == NULL ) {
      // always optimize final methods, private methods or methods with no
      // subclasses.
      return new CHAResult(actual_receiver, name, signature, NULL, NULL, m);
    } 
    if (!UseCHA) {
      // don't optimize this call
      return new CHAResult(actual_receiver, name, signature, NULL, NULL, m, false);
    }
  }

  // If the method is abstract then each non-abstract subclass must implement 
  // the method and inlining is not possible.  If there is exactly 1 subclass
  // then there can be only 1 implementation and we are OK.  
  // (This test weakens CHA slightly, for the sake of the old dependency mechanism.)
  if( !m.is_null() && m()->is_abstract() ) {// Method is abstract?
    Klass *sr = Klass::cast(static_receiver());
    if( sr == sr->up_cast_abstract() )
      return new CHAResult(actual_receiver, name, signature, NULL, NULL, m, false);
    // Fall into the next code; it will find the one implementation
    // and that implementation is correct.
  }

  _used = true;
  GrowableArray<methodHandle>* methods  = new GrowableArray<methodHandle>(CHA::max_result());
  GrowableArray<KlassHandle>* receivers = new GrowableArray<KlassHandle>(CHA::max_result());

  // Since 'm' is visible from the actual receiver we can call it if the
  // runtime receiver class does not override 'm'.  
  if( !m.is_null() && m()->method_holder() != actual_receiver() &&
      !m->is_abstract() ) {
    receivers->push(actual_receiver);
    methods->push(m);
  }
  if (static_receiver->is_interface()) {
    instanceKlassHandle sr = static_receiver();
    process_interface(sr, receivers, methods, name, signature);
  } else {
    process_class(static_receiver, receivers, methods, name, signature);
  }

  methodHandle dummy;
  CHAResult* res = new CHAResult(actual_receiver, name, signature, receivers, methods, dummy);

  //res->print();
  return res;
}

void CHA::process_class(KlassHandle r, GrowableArray<KlassHandle>* receivers, GrowableArray<methodHandle>* methods, symbolHandle name, symbolHandle signature) {    
  // recursively add non-abstract subclasses of r to receivers list
  assert(!r->is_interface(), "should call process_interface instead");
  for (Klass* s = r->subklass(); s != NULL && !methods->is_full(); s = s->next_sibling()) {
    // preorder traversal, so check subclasses first
    if (s->is_interface()) {
      // can only happen if r == Object
      assert(r->superklass() == NULL, "must be klass Object");
    } else {
      process_class(s, receivers, methods, name, signature);
    }
  }
  // now check r itself (after subclasses because of preorder)
  if (!methods->is_full()) {
    // don't add abstract classes to receivers list
    // (but still consider their methods -- they may be non-abstract)
    if (!receivers->is_full() && !r->is_abstract()) {
      // don't duplicate the actual receiver
      if (!receivers->contains(r)) receivers->push(r);
    }
    methodOop m = NULL;
    if (r->oop_is_instance()) m = instanceKlass::cast(r())->find_method(name(), signature()); 
    if (m != NULL && !m->is_abstract()) {
      if (!methods->contains(m)) methods->push(m);
    }
  }
}

void CHA::process_interface(instanceKlassHandle r, GrowableArray<KlassHandle>* receivers, GrowableArray<methodHandle>* methods, 
                            symbolHandle name, symbolHandle signature) {
  // recursively add non-abstract implementors of interface r to receivers list
  assert(r->is_interface(), "should call process_class instead");
  
  // We only store the implementors for an interface, if there is exactly one implementor  
  klassOop k = NULL;
  if (r->nof_implementors() == 1)  k = r->implementor(0);
  if (k == NULL)  methods->clear();  // no news is bad news
  if (k != NULL && !methods->is_full()) {   
    instanceKlass* kl = instanceKlass::cast(k);
    assert(kl->oop_is_instance(), "primitive klasses don't implement interfaces");
    assert(!kl->is_interface(), "must be a real klass");
    process_class(kl, receivers, methods, name, signature);
  }

  // there are no links to subinterfaces
  assert(r->subklass() == NULL, "interfaces have no subclasses");
}


CHAResult::CHAResult(KlassHandle r, symbolHandle n, symbolHandle s,
                     GrowableArray<KlassHandle>* rs, GrowableArray<methodHandle>* ms, 
                     methodHandle target, bool v) :
  _receiver(r), _name(n), _signature(s), _receivers(rs), _target_methods(ms), _valid(v), _target(target) {}

bool CHAResult::is_monomorphic() const {
  // note: check number of target methods, not number of receivers
  // (send can be monomorphic even with many receiver classes, if all inherit same method)
  return _valid && (_target_methods == NULL || _target_methods->length() == 1);
}

methodHandle CHAResult::monomorphic_target() const {
  assert(is_monomorphic(), "not monomorphic");
  if (_target_methods != NULL) {
    assert(_target_methods->length() == 1, "expected single target");
    return _target_methods->first();
  } else {
    // final method
    //    assert(_target->is_final_method(), "expected final method");
    return _target;
  }
}

KlassHandle CHAResult::monomorphic_receiver() const {
  assert(is_monomorphic(), "not monomorphic");
  if (_receivers != NULL) {
    // since all lookups will find the same method, it doesn't matter that much
    // which klass we return; for beauty's sake, return the target's method holder
    // (note: don't return _receiver -- its method may be abstract)
    return _target_methods->first()->method_holder();
  } else {
    // final method
    //    assert(_target->is_final_method(), "expected final method");
    return _receiver;
  }
}

void CHAResult::print() {
  tty->print("(CHAResult*)%#x : ", this); 
  (instanceKlass::cast(_receiver()))->name()->print_value();
  tty->print("::");
  _name()->print_value();
  tty->print_cr(" %s", _valid ? "(Found)" : "(Not found)");
  if (_receivers != NULL) 
    tty->print("%d receiver klasses ", _receivers->length());
  if (_target_methods != NULL) 
    tty->print("%d target methods %s", _target_methods->length(), 
                _target_methods->is_full() ? "(FULL)" : "");
  if (is_monomorphic()) {
    methodHandle target = monomorphic_target();
    tty->print("monomorphic target method : ");
    target->print_short_name(tty);
    if (target->is_final())
      tty->print(" (final)");
    if (target->is_abstract())
      tty->print(" (abstract)");
  }
  tty->cr();
}
