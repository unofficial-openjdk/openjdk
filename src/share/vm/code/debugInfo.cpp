#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)debugInfo.cpp	1.34 07/05/05 17:05:19 JVM"
#endif
/*
 * Copyright 1997-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

# include "incls/_precompiled.incl"
# include "incls/_debugInfo.cpp.incl"

// Comstructors

DebugInfoWriteStream::DebugInfoWriteStream(DebugInformationRecorder* recorder, int initial_size)
: CompressedWriteStream(initial_size) {
  _recorder = recorder;
}

// Serializing oops
void DebugInfoWriteStream::write_handle(jobject h) {
  write_int(recorder()->oop_recorder()->find_index(h));
}

// Serializing scope values

enum { LOCATION_CODE = 0, CONSTANT_INT_CODE = 1,  CONSTANT_OOP_CODE = 2,
                          CONSTANT_LONG_CODE = 3, CONSTANT_DOUBLE_CODE = 4 };

ScopeValue* ScopeValue::read_from(DebugInfoReadStream* stream) {
  ScopeValue* result = NULL;
  switch(stream->read_int()) {
   case LOCATION_CODE:        result = new LocationValue(stream);        break;
   case CONSTANT_INT_CODE:    result = new ConstantIntValue(stream);     break;
   case CONSTANT_OOP_CODE:    result = new ConstantOopReadValue(stream); break;
   case CONSTANT_LONG_CODE:   result = new ConstantLongValue(stream);    break;
   case CONSTANT_DOUBLE_CODE: result = new ConstantDoubleValue(stream);  break;
   default: ShouldNotReachHere();            
  }
  return result;
}

// LocationValue

LocationValue::LocationValue(DebugInfoReadStream* stream) {
  _location = Location(stream);
}

void LocationValue::write_on(DebugInfoWriteStream* stream) {
  stream->write_int(LOCATION_CODE);
  location().write_on(stream);
}

void LocationValue::print_on(outputStream* st) const {
  location().print_on(st);
}

// ConstantIntValue

ConstantIntValue::ConstantIntValue(DebugInfoReadStream* stream) {
  _value = stream->read_signed_int();
}

void ConstantIntValue::write_on(DebugInfoWriteStream* stream) {
  stream->write_int(CONSTANT_INT_CODE);
  stream->write_signed_int(value());
}

void ConstantIntValue::print_on(outputStream* st) const {
  st->print("%d", value());
}

// ConstantLongValue

ConstantLongValue::ConstantLongValue(DebugInfoReadStream* stream) {
  _value = stream->read_long();
}

void ConstantLongValue::write_on(DebugInfoWriteStream* stream) {
  stream->write_int(CONSTANT_LONG_CODE);
  stream->write_long(value());
}

void ConstantLongValue::print_on(outputStream* st) const {
  st->print(INT64_FORMAT, value());
}

// ConstantDoubleValue

ConstantDoubleValue::ConstantDoubleValue(DebugInfoReadStream* stream) {
  _value = stream->read_double();
}

void ConstantDoubleValue::write_on(DebugInfoWriteStream* stream) {
  stream->write_int(CONSTANT_DOUBLE_CODE);
  stream->write_double(value());
}

void ConstantDoubleValue::print_on(outputStream* st) const {
  st->print("%f", value());
}

// ConstantOopWriteValue

void ConstantOopWriteValue::write_on(DebugInfoWriteStream* stream) {
  stream->write_int(CONSTANT_OOP_CODE);
  stream->write_handle(value());
}

void ConstantOopWriteValue::print_on(outputStream* st) const {
  JNIHandles::resolve(value())->print_value_on(st);
}


// ConstantOopReadValue

ConstantOopReadValue::ConstantOopReadValue(DebugInfoReadStream* stream) {
  _value = Handle(stream->read_oop());
}

void ConstantOopReadValue::write_on(DebugInfoWriteStream* stream) {
  ShouldNotReachHere();
}

void ConstantOopReadValue::print_on(outputStream* st) const {
  value()()->print_value_on(st);
}


// MonitorValue

MonitorValue::MonitorValue(ScopeValue* owner, Location basic_lock) {
  _owner       = owner;
  _basic_lock  = basic_lock;
}

MonitorValue::MonitorValue(DebugInfoReadStream* stream) {
  _basic_lock  = Location(stream);
  _owner       = ScopeValue::read_from(stream);
}

void MonitorValue::write_on(DebugInfoWriteStream* stream) {
  _basic_lock.write_on(stream);
  _owner->write_on(stream);
}

#ifndef PRODUCT
void MonitorValue::print_on(outputStream* st) const {
  st->print("monitor{");
  owner()->print_on(st);
  st->print(",");
  basic_lock().print_on(st);
  st->print("}");
}
#endif

