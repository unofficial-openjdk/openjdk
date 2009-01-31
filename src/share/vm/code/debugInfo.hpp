#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)debugInfo.hpp	1.34 07/05/05 17:05:20 JVM"
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

// Classes used for serializing debugging information.
// These abstractions are introducted to provide symmetric 
// read and write operations.

// ScopeValue        describes the value of a variable/expression in a scope
// - LocationValue   describes a value in a given location (in frame or register)
// - ConstantValue   describes a constant

class ScopeValue: public ResourceObj {
 public:
  // Testers
  virtual bool is_location() const { return false; }
  virtual bool is_constant_int() const { return false; }
  virtual bool is_constant_double() const { return false; }
  virtual bool is_constant_long() const { return false; }
  virtual bool is_constant_oop() const { return false; }
  virtual bool equals(ScopeValue* other) const { return false; }

  // Serialization of debugging information
  virtual void write_on(DebugInfoWriteStream* stream) = 0;
  static ScopeValue* read_from(DebugInfoReadStream* stream);
};


// A Location value describes a value in a given location; i.e. the corresponding
// logical entity (e.g., a method temporary) lives in this location.

class LocationValue: public ScopeValue {
 private:
  Location  _location;
 public:
  LocationValue(Location location)           { _location = location; }
  bool      is_location() const              { return true; }
  Location  location() const                 { return _location; }

  // Serialization of debugging information
  LocationValue(DebugInfoReadStream* stream);
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

// A ConstantIntValue describes a constant int; i.e., the corresponding logical entity
// is either a source constant or its computation has been constant-folded.

class ConstantIntValue: public ScopeValue {
 private:
  jint _value;
 public:
  ConstantIntValue(jint value)         { _value = value; }
  jint value() const                   { return _value;  }
  bool is_constant_int() const         { return true;    }
  bool equals(ScopeValue* other) const { return false;   }

  // Serialization of debugging information
  ConstantIntValue(DebugInfoReadStream* stream);
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

class ConstantLongValue: public ScopeValue {
 private:
  jlong _value;
 public:
  ConstantLongValue(jlong value)       { _value = value; }
  jlong value() const                  { return _value;  }
  bool is_constant_long() const        { return true;    }
  bool equals(ScopeValue* other) const { return false;   }

  // Serialization of debugging information
  ConstantLongValue(DebugInfoReadStream* stream);
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

class ConstantDoubleValue: public ScopeValue {
 private:
  jdouble _value;
 public:
  ConstantDoubleValue(jdouble value)   { _value = value; }
  jdouble value() const                { return _value;  }
  bool is_constant_double() const      { return true;    }
  bool equals(ScopeValue* other) const { return false;   }

  // Serialization of debugging information
  ConstantDoubleValue(DebugInfoReadStream* stream);
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

// A ConstantOopWriteValue is created by the compiler to
// be written as debugging information.

class ConstantOopWriteValue: public ScopeValue {
 private:
  jobject _value;
 public:
  ConstantOopWriteValue(jobject value) { _value = value; }
  jobject value() const                { return _value;  }
  bool is_constant_oop() const         { return true;    }
  bool equals(ScopeValue* other) const { return false;   }

  // Serialization of debugging information
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

// A ConstantOopReadValue is created by the VM when reading
// debug information

class ConstantOopReadValue: public ScopeValue {
 private:
  Handle _value;
 public:
  Handle value() const                 { return _value;  }
  bool is_constant_oop() const         { return true;    }
  bool equals(ScopeValue* other) const { return false;   }

  // Serialization of debugging information
  ConstantOopReadValue(DebugInfoReadStream* stream);
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

// MonitorValue describes the pair used for monitor_enter and monitor_exit.

class MonitorValue: public ResourceObj {
 private:
  ScopeValue* _owner;
  Location    _basic_lock;
 public:
  // Constructor
  MonitorValue(ScopeValue* owner, Location basic_lock);

  // Accessors
  ScopeValue*  owner()      const { return _owner; }
  Location     basic_lock() const { return _basic_lock;  }

  // Serialization of debugging information
  MonitorValue(DebugInfoReadStream* stream);
  void write_on(DebugInfoWriteStream* stream);

  // Printing
  void print_on(outputStream* st) const;
};

// DebugInfoReadStream specializes CompressedReadStream for reading
// debugging information. Used by ScopeDesc.

class DebugInfoReadStream : public CompressedReadStream {
 private:
  const nmethod* _code;
  const nmethod* code() const { return _code; }
 public:
  DebugInfoReadStream(const nmethod* code, int offset) :
    CompressedReadStream(code->scopes_data_begin(), offset) { 
    _code = code; 
  } ;

  oop read_oop() { 
    return code()->oop_at(read_int()); 
  } 
  // BCI encoding is mostly unsigned, but -1 is a distinguished value
  int read_bci() { return read_int() + InvocationEntryBci; }
};

// DebugInfoWriteStream specializes CompressedWriteStream for
// writing debugging information. Used by ScopeDescRecorder.

class DebugInfoWriteStream : public CompressedWriteStream {
 private:
  DebugInformationRecorder* _recorder;
  DebugInformationRecorder* recorder() const { return _recorder; }
 public:
  DebugInfoWriteStream(DebugInformationRecorder* recorder, int initial_size);
  void write_handle(jobject h);
  void write_bci(int bci) { write_int(bci - InvocationEntryBci); }
};

