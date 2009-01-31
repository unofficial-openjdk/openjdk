#ifdef USE_PRAGMA_IDENT_HDR
#pragma ident "@(#)assembler_amd64.hpp	1.41 07/06/13 16:23:17 JVM"
#endif
/*
 * Copyright 2003-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

class BiasedLockingCounters;

// Contains all the definitions needed for amd64 assembly code generation.

// Calling convention
class Argument VALUE_OBJ_CLASS_SPEC 
{
 public:
  enum {
#ifdef _WIN64
    n_int_register_parameters_c   = 4, // rcx, rdx, r8, r9 (c_rarg0, c_rarg1, ...)
    n_float_register_parameters_c = 4,  // xmm0 - xmm3 (c_farg0, c_farg1, ... )
#else
    n_int_register_parameters_c   = 6, // rdi, rsi, rdx, rcx, r8, r9 (c_rarg0, c_rarg1, ...)
    n_float_register_parameters_c = 8,  // xmm0 - xmm7 (c_farg0, c_farg1, ... )
#endif
    n_int_register_parameters_j   = 6, // j_rarg0, j_rarg1, ...
    n_float_register_parameters_j = 8  // j_farg0, j_farg1, ...
  };
};


// Symbolically name the register arguments used by the c calling convention.
// Windows is different from linux/solaris. So much for standards...

#ifdef _WIN64

REGISTER_DECLARATION(Register, c_rarg0, rcx);
REGISTER_DECLARATION(Register, c_rarg1, rdx);
REGISTER_DECLARATION(Register, c_rarg2, r8);
REGISTER_DECLARATION(Register, c_rarg3, r9);

REGISTER_DECLARATION(FloatRegister, c_farg0, xmm0);
REGISTER_DECLARATION(FloatRegister, c_farg1, xmm1);
REGISTER_DECLARATION(FloatRegister, c_farg2, xmm2);
REGISTER_DECLARATION(FloatRegister, c_farg3, xmm3);

#else

REGISTER_DECLARATION(Register, c_rarg0, rdi);
REGISTER_DECLARATION(Register, c_rarg1, rsi);
REGISTER_DECLARATION(Register, c_rarg2, rdx);
REGISTER_DECLARATION(Register, c_rarg3, rcx);
REGISTER_DECLARATION(Register, c_rarg4, r8);
REGISTER_DECLARATION(Register, c_rarg5, r9);

REGISTER_DECLARATION(FloatRegister, c_farg0, xmm0);
REGISTER_DECLARATION(FloatRegister, c_farg1, xmm1);
REGISTER_DECLARATION(FloatRegister, c_farg2, xmm2);
REGISTER_DECLARATION(FloatRegister, c_farg3, xmm3);
REGISTER_DECLARATION(FloatRegister, c_farg4, xmm4);
REGISTER_DECLARATION(FloatRegister, c_farg5, xmm5);
REGISTER_DECLARATION(FloatRegister, c_farg6, xmm6);
REGISTER_DECLARATION(FloatRegister, c_farg7, xmm7);

#endif

// Symbolically name the register arguments used by the Java calling convention.
// We have control over the convention for java so we can do what we please.
// What pleases us is to offset the java calling convention so that when
// we call a suitable jni method the arguments are lined up and we don't
// have to do little shuffling. A suitable jni method is non-static and a
// small number of arguments (two fewer args on windows)
//
//        |-------------------------------------------------------|
//        | c_rarg0   c_rarg1  c_rarg2 c_rarg3 c_rarg4 c_rarg5    | 
//        |-------------------------------------------------------|
//        | rcx       rdx      r8      r9      rdi*    rsi*       | windows (* not a c_rarg)
//        | rdi       rsi      rdx     rcx     r8      r9         | solaris/linux
//        |-------------------------------------------------------|
//        | j_rarg5   j_rarg0  j_rarg1 j_rarg2 j_rarg3 j_rarg4    |
//        |-------------------------------------------------------|

REGISTER_DECLARATION(Register, j_rarg0, c_rarg1);
REGISTER_DECLARATION(Register, j_rarg1, c_rarg2);
REGISTER_DECLARATION(Register, j_rarg2, c_rarg3);
// Windows runs out of register args here
#ifdef _WIN64
REGISTER_DECLARATION(Register, j_rarg3, rdi);
REGISTER_DECLARATION(Register, j_rarg4, rsi);
#else
REGISTER_DECLARATION(Register, j_rarg3, c_rarg4);
REGISTER_DECLARATION(Register, j_rarg4, c_rarg5);
#endif /* _WIN64 */
REGISTER_DECLARATION(Register, j_rarg5, c_rarg0);

REGISTER_DECLARATION(FloatRegister, j_farg0, xmm0);
REGISTER_DECLARATION(FloatRegister, j_farg1, xmm1);
REGISTER_DECLARATION(FloatRegister, j_farg2, xmm2);
REGISTER_DECLARATION(FloatRegister, j_farg3, xmm3);
REGISTER_DECLARATION(FloatRegister, j_farg4, xmm4);
REGISTER_DECLARATION(FloatRegister, j_farg5, xmm5);
REGISTER_DECLARATION(FloatRegister, j_farg6, xmm6);
REGISTER_DECLARATION(FloatRegister, j_farg7, xmm7);

REGISTER_DECLARATION(Register, rscratch1, r10);  // volatile
REGISTER_DECLARATION(Register, rscratch2, r11);  // volatile

REGISTER_DECLARATION(Register, r15_thread, r15); // callee-saved


// Address is an abstraction used to represent a memory location
// using any of the amd64 addressing modes with one object.
//
// Note: A register location is represented via a Register, not
//       via an address for efficiency & simplicity reasons.

class Address VALUE_OBJ_CLASS_SPEC {
 public:
  enum ScaleFactor {
    no_scale = -1,
    times_1  =  0,
    times_2  =  1,
    times_4  =  2,
    times_8  =  3
  };

 private:
  Register         _base;
  Register         _index;
  ScaleFactor      _scale;
  int              _disp;
  RelocationHolder _rspec;

  address          _target; // only used for RIP-relative addressing

 public:
  // creation
  Address()
    : _base(noreg),
      _index(noreg),
      _scale(no_scale),
      _disp(0),
      _target(NULL)
  {}

  // NOTE: this constructor takes a dummy argument so that we don't
  // have implicit type conversion going on behind our pack. Possibly
  // could be private instead.
  Address(int disp, bool dummy)
    : _base(noreg),
      _index(noreg),
      _scale(no_scale),
      _disp(disp),
      _target(NULL)
  {}

  Address(address target, relocInfo::relocType rtype);

  Address(address target, RelocationHolder const& rspec)
    : _base(noreg),
      _index(noreg),
      _scale(no_scale),
      _rspec(rspec),
      _disp(0),
      _target(target)
  {}

  Address(Register base, int disp = 0)
    : _base(base),
      _index(noreg),
      _scale(no_scale),
      _disp(disp),
      _target(NULL)
  {}

  Address(Register base, Register index, ScaleFactor scale, int disp = 0)
    : _base (base),
      _index(index),
      _scale(scale),
      _disp (disp),
      _target(NULL) {
    assert(!index->is_valid() == (scale == Address::no_scale), 
           "inconsistent address");
  }

  // The following two overloads are used in connection with the
  // ByteSize type (see sizes.hpp).  They simplify the use of
  // ByteSize'd arguments in assembly code. Note that their equivalent
  // for the optimized build are the member functions with int disp
  // argument since ByteSize is mapped to an int type in that case.
  //
  // Note: DO NOT introduce similar overloaded functions for WordSize
  // arguments as in the optimized mode, both ByteSize and WordSize
  // are mapped to the same type and thus the compiler cannot make a
  // distinction anymore (=> compiler errors).

#ifdef ASSERT
  Address(Register base, ByteSize disp)
    : _base(base),
      _index(noreg),
      _scale(no_scale),
      _disp(in_bytes(disp)),
      _target(NULL)
  {}

  Address(Register base, Register index, ScaleFactor scale, ByteSize disp)
    : _base(base),
      _index(index),
      _scale(scale),
      _disp(in_bytes(disp)),
      _target(NULL) {
    assert(!index->is_valid() == (scale == Address::no_scale),
           "inconsistent address");
  }
#endif // ASSERT

  // accessors
  bool uses(Register reg) const { 
    return _base == reg || _index == reg; 
  }


 private:
  bool base_needs_rex() const {
    return _base != noreg && _base->encoding() >= 8;
  }

  bool index_needs_rex() const {
    return _index != noreg &&_index->encoding() >= 8;
  }

  bool is_rip_relative() const {
    return _target != NULL;
  }

  // Return true if the relocation is such that it will deserve a 64-bit
  // relocation. We assume that everything in the code cache is reachable
  // from everything else in the code cache but that from the code cache
  // to external_word or runtime_call is too far.
  bool is_far() const {
    relocInfo::relocType r = _rspec.type();
    return r == relocInfo::runtime_call_type || r == relocInfo::external_word_type; 
  }
  relocInfo::relocType reloc() const { return _rspec.type(); }

  friend class Assembler;
  friend class MacroAssembler;
};


// The amd64 Assembler: Pure assembler doing NO optimizations on
// the instruction level (e.g. mov rax, 0 is not translated into xor
// rax, rax!); i.e., what you write is what you get. The Assembler is
// generating code into a CodeBuffer.

const int FPUStateSizeInWords = 512 / wordSize;

class Assembler 
  : public AbstractAssembler  
{
  friend class AbstractAssembler; // for the non-virtual hack
  friend class StubGenerator; 

 protected:
#ifdef ASSERT
  void check_relocation(RelocationHolder const& rspec, int format);
#endif

  inline void emit_long64(jlong x);

  void emit_data(jint data, relocInfo::relocType rtype, int format = 1);
  void emit_data(jint data, RelocationHolder const& rspec, int format = 1);
  void emit_data64(jlong data, relocInfo::relocType rtype, int format = 0);
  void emit_data64(jlong data, RelocationHolder const& rspec, int format = 0);

  // Helper functions for groups of instructions
  void emit_arith_b(int op1, int op2, Register dst, int imm8);

  void emit_arith(int op1, int op2, Register dst, int imm32);
  void emit_arith(int op1, int op2, Register dst, Register src);

  void emit_operand(Register reg, 
                    Register base, Register index, Address::ScaleFactor scale, 
                    int disp,
                    address target,
                    RelocationHolder const& rspec,
                    int rip_relative_correction = 0);
  void emit_operand(Register reg, Address adr,
                    int rip_relative_correction = 0);
  void emit_operand(FloatRegister reg, 
                    Register base, Register index, Address::ScaleFactor scale,
                    int disp,
                    address target,
                    RelocationHolder const& rspec,
                    int rip_relative_correction = 0);
  void emit_operand(FloatRegister reg, Address adr,
                    int rip_relative_correction = 0);

  // Immediate-to-memory forms
  void emit_arith_operand(int op1, Register rm, Address adr, int imm32);

  void emit_farith(int b1, int b2, int i);

  // Helper routine used to determined if we can reach
  // an address via rip relative addressing.
  // As a convienence, we return true if the argument is
  // not a rip address, since it will be reachable.
  bool is_reachable(Address adr);

 public:
  enum Condition { // The amd64 condition codes used for conditional jumps/moves.
    zero          = 0x4,
    notZero       = 0x5,
    equal         = 0x4,
    notEqual      = 0x5,
    less          = 0xc,
    lessEqual     = 0xe,
    greater       = 0xf,
    greaterEqual  = 0xd,
    below         = 0x2,
    belowEqual	  = 0x6,
    above         = 0x7,
    aboveEqual	  = 0x3,
    overflow      = 0x0,
    noOverflow	  = 0x1,
    carrySet      = 0x2,
    carryClear	  = 0x3,
    negative      = 0x8,
    positive      = 0x9,
    parity        = 0xa,
    noParity      = 0xb
  };

  enum Prefix {
    // segment overrides
    // XXX remove segment prefixes
    CS_segment = 0x2e,
    SS_segment = 0x36,
    DS_segment = 0x3e,
    ES_segment = 0x26,
    FS_segment = 0x64,
    GS_segment = 0x65,

    REX        = 0x40,

    REX_B      = 0x41,
    REX_X      = 0x42,
    REX_XB     = 0x43,
    REX_R      = 0x44,
    REX_RB     = 0x45,
    REX_RX     = 0x46,
    REX_RXB    = 0x47,

    REX_W      = 0x48,

    REX_WB     = 0x49,
    REX_WX     = 0x4A,
    REX_WXB    = 0x4B,
    REX_WR     = 0x4C,
    REX_WRB    = 0x4D,
    REX_WRX    = 0x4E,
    REX_WRXB   = 0x4F
  };

  enum WhichOperand {
    // input to locate_operand, and format code for relocations
    imm64_operand  = 0,          // embedded 64-bit immediate operand
    disp32_operand = 1,          // embedded 32-bit displacement
    call32_operand = 2,          // embedded 32-bit self-relative displacement
    _WhichOperand_limit = 3
  };

  // Creation
  Assembler(CodeBuffer* code) 
    : AbstractAssembler(code) 
  {}

  // Decoding
  static address locate_operand(address inst, WhichOperand which);
  static address locate_next_instruction(address inst);

  // Utilities

 static bool is_simm(int64_t x, int nbits) { return -( CONST64(1) << (nbits-1) )  <= x   &&   x  <  ( CONST64(1) << (nbits-1) ); }
 static bool is_simm32 (int64_t x) { return x == (int64_t)(int32_t)x; }


  // Stack
  void pushaq();
  void popaq();

  void pushfq();
  void popfq();

  void pushq(int imm32);
  void pushq(Register src);
  void pushq(Address src);

  void popq(Register dst);
  void popq(Address dst);

  // Instruction prefixes
  void prefix(Prefix p);

  // Moves
  void movb(Register dst, Address src);
  void movb(Address dst, int imm8);
  void movb(Address dst, Register src);

  void movw(Address dst, int imm16);
  void movw(Register dst, Address src);
  void movw(Address dst, Register src);

  void movl(Register dst, int imm32);
  void movl(Register dst, Register src);
  void movl(Register dst, Address src);
  void movl(Address dst, int imm32);
  void movl(Address dst, Register src);

  void movq(Register dst, int64_t imm64);
  void movq(Register dst, address imm64, relocInfo::relocType rtype);
  void movq(Register dst, Register src);
  void movq(Register dst, jobject obj);
  void movq(Register dst, Address src);
  void movq(Address dst, int64_t imm64);
  void movq(Address dst, Register src);

  void movsbl(Register dst, Address src);
  void movsbl(Register dst, Register src);
  void movswl(Register dst, Address src);
  void movswl(Register dst, Register src);
  void movslq(Register dst, Address src);
  void movslq(Register dst, Register src);

  void movzbl(Register dst, Address src);
  void movzbl(Register dst, Register src);
  void movzwl(Register dst, Address src);
  void movzwl(Register dst, Register src);

 protected: // Avoid using the next instructions directly.
  // New cpus require use of movsd and movss to avoid partial register stall
  // when loading from memory. But for old Opteron use movlpd instead of movsd.
  // The selection is done in MacroAssembler::movdbl() and movflt().
  void movss(FloatRegister dst, FloatRegister src);
  void movss(FloatRegister dst, Address src);
  void movss(Address dst, FloatRegister src);
  void movsd(FloatRegister dst, FloatRegister src);
  void movsd(Address dst, FloatRegister src);
  void movsd(FloatRegister dst, Address src);
  void movlpd(FloatRegister dst, Address src);
  // New cpus require use of movaps and movapd to avoid partial register stall
  // when moving between registers.
  void movapd(FloatRegister dst, FloatRegister src);
  void movaps(FloatRegister dst, FloatRegister src);
 public:

  void movdl(FloatRegister dst, Register src);
  void movdl(Register dst, FloatRegister src);
  void movdq(FloatRegister dst, Register src);
  void movdq(Register dst, FloatRegister src);

  void cmovl(Condition cc, Register dst, Register src);
  void cmovl(Condition cc, Register dst, Address src);
  void cmovq(Condition cc, Register dst, Register src);
  void cmovq(Condition cc, Register dst, Address src);

  // Prefetches
 private:
  void prefetch_prefix(Address src);
 public:
  void prefetcht0(Address src);
  void prefetcht1(Address src);
  void prefetcht2(Address src);
  void prefetchnta(Address src);
  void prefetchw(Address src);

  // Arithmetics
  void adcl(Register dst, int imm32);
  void adcl(Register dst, Address src);
  void adcl(Register dst, Register src);
  void adcq(Register dst, int imm32);
  void adcq(Register dst, Address src);
  void adcq(Register dst, Register src);

  void addl(Address dst, int imm32);
  void addl(Address dst, Register src);
  void addl(Register dst, int imm32);
  void addl(Register dst, Address src);
  void addl(Register dst, Register src);
  void addq(Address dst, int imm32);
  void addq(Address dst, Register src);
  void addq(Register dst, int imm32);
  void addq(Register dst, Address src);
  void addq(Register dst, Register src);

  void andl(Register dst, int imm32);
  void andl(Register dst, Address src);
  void andl(Register dst, Register src);
  void andq(Register dst, int imm32);
  void andq(Register dst, Address src);
  void andq(Register dst, Register src);

  void cmpb(Address dst, int imm8);
  void cmpl(Address dst, int imm32);
  void cmpl(Register dst, int imm32);
  void cmpl(Register dst, Register src);
  void cmpl(Register dst, Address src);
  void cmpq(Address dst, int imm32);
  void cmpq(Register dst, int imm32);
  void cmpq(Register dst, Register src);
  void cmpq(Register dst, Address src);

  void ucomiss(FloatRegister dst, FloatRegister src);
  void ucomisd(FloatRegister dst, FloatRegister src);

 protected:
  // Don't use next inc() and dec() methods directly. INC & DEC instructions 
  // could cause a partial flag stall since they don't set CF flag.
  // Use MacroAssembler::decrement() & MacroAssembler::increment() methods
  // which call inc() & dec() or add() & sub() in accordance with 
  // the product flag UseIncDec value.

  void decl(Register dst);
  void decl(Address dst);
  void decq(Register dst);
  void decq(Address dst);

  void incl(Register dst);
  void incl(Address dst);
  void incq(Register dst);
  void incq(Address dst);

 public:
  void idivl(Register src);
  void idivq(Register src);
  void cdql();
  void cdqq();

  void imull(Register dst, Register src);
  void imull(Register dst, Register src, int value);
  void imulq(Register dst, Register src);
  void imulq(Register dst, Register src, int value);

  void leal(Register dst, Address src);
  void leaq(Register dst, Address src);

  void mull(Address src);
  void mull(Register src);

  void negl(Register dst);
  void negq(Register dst);

  void notl(Register dst);
  void notq(Register dst);

  void orl(Address dst, int imm32);
  void orl(Register dst, int imm32);
  void orl(Register dst, Address src);
  void orl(Register dst, Register src);
  void orq(Address dst, int imm32);
  void orq(Register dst, int imm32);
  void orq(Register dst, Address src);
  void orq(Register dst, Register src);
  
  void rcll(Register dst, int imm8);
  void rclq(Register dst, int imm8);

  void sarl(Register dst, int imm8);
  void sarl(Register dst);
  void sarq(Register dst, int imm8);
  void sarq(Register dst);

  void sbbl(Address dst, int imm32);
  void sbbl(Register dst, int imm32);
  void sbbl(Register dst, Address src);
  void sbbl(Register dst, Register src);
  void sbbq(Address dst, int imm32);
  void sbbq(Register dst, int imm32);
  void sbbq(Register dst, Address src);
  void sbbq(Register dst, Register src);

  void shll(Register dst, int imm8);
  void shll(Register dst);
  void shlq(Register dst, int imm8);
  void shlq(Register dst);

  void shrl(Register dst, int imm8);
  void shrl(Register dst);
  void shrq(Register dst, int imm8);
  void shrq(Register dst);

  void subl(Address dst, int imm32);
  void subl(Address dst, Register src);
  void subl(Register dst, int imm32);
  void subl(Register dst, Address src);
  void subl(Register dst, Register src);
  void subq(Address dst, int imm32);
  void subq(Address dst, Register src);
  void subq(Register dst, int imm32);
  void subq(Register dst, Address src);
  void subq(Register dst, Register src);

  void testb(Register dst, int imm8);
  void testl(Register dst, int imm32);
  void testl(Register dst, Register src);
  void testq(Register dst, int imm32);
  void testq(Register dst, Register src);

  void xaddl(Address dst, Register src);
  void xaddq(Address dst, Register src);

  void xorl(Register dst, int imm32);
  void xorl(Register dst, Address src);
  void xorl(Register dst, Register src);
  void xorq(Register dst, int imm32);
  void xorq(Register dst, Address src);
  void xorq(Register dst, Register src);

  // Miscellaneous
  void bswapl(Register reg);
  void bswapq(Register reg);
  void lock();

  void xchgl(Register reg, Address adr);
  void xchgl(Register dst, Register src);
  void xchgq(Register reg, Address adr);
  void xchgq(Register dst, Register src);

  void cmpxchgl(Register reg, Address adr);
  void cmpxchgq(Register reg, Address adr);

  void hlt();
  void int3();
  void nop(int i = 1);
  void ret(int imm16);
  void smovl();
  void rep_movl();
  void rep_movq();
  void rep_set();
  void repne_scan();
  void setb(Condition cc, Register dst);

  void clflush(Address adr);

  enum Membar_mask_bits {
    StoreStore = 1 << 3,
    LoadStore  = 1 << 2,
    StoreLoad  = 1 << 1,
    LoadLoad   = 1 << 0
  };

  // Serializes memory.
  void membar(Membar_mask_bits order_constraint)
  {
    // We only have to handle StoreLoad and LoadLoad
    if (order_constraint & StoreLoad) {
      // MFENCE subsumes LFENCE
      mfence();
    } /* [jk] not needed currently: else if (order_constraint & LoadLoad) {
         lfence();
    } */
  }

  void lfence()
  {
    emit_byte(0x0F);
    emit_byte(0xAE);
    emit_byte(0xE8);
  }

  void mfence()
  {
    emit_byte(0x0F);
    emit_byte(0xAE);
    emit_byte(0xF0);
  }

  // Identify processor type and features
  void cpuid() 
  {
    emit_byte(0x0F);
    emit_byte(0xA2);
  }

  void cld() {
    emit_byte(0xfc);
  }

  void std() {
    emit_byte(0xfd);
  }


  // Calls
  void call(Label& L, relocInfo::relocType rtype);
  void call(address entry, relocInfo::relocType rtype);
  void call(address entry, RelocationHolder const& rspec);
  void call(Register reg,  relocInfo::relocType rtype);
  void call(Address adr);
  
  // Jumps
  void jmp(address entry, relocInfo::relocType rtype);
  void jmp(Register reg,  relocInfo::relocType rtype = relocInfo::none);
  void jmp(Address adr);

  // Label operations & relative jumps (PPUM Appendix D)
  // unconditional jump to L
  void jmp(Label& L, relocInfo::relocType rtype = relocInfo::none); 

  // Force an 8-bit jump offset
  void jmpb(address entry); 

  // Unconditional 8-bit offset jump to L.
  // WARNING: be very careful using this for forward jumps.  If the label is
  // not bound within an 8-bit offset of this instruction, a run-time error
  // will occur.
  void jmpb(Label& L);   

  // jcc is the generic conditional branch generator to run- time
  // routines, jcc is used for branches to labels. jcc takes a branch
  // opcode (cc) and a label (L) and generates either a backward
  // branch or a forward branch and links it to the label fixup
  // chain. Usage:
  //
  // Label L;      // unbound label
  // jcc(cc, L);   // forward branch to unbound label
  // bind(L);      // bind label to the current pc
  // jcc(cc, L);   // backward branch to bound label
  // bind(L);      // illegal: a label may be bound only once
  //
  // Note: The same Label can be used for forward and backward branches
  // but it may be bound only once.

  void jcc(Condition cc, address dst, 
           relocInfo::relocType rtype = relocInfo::runtime_call_type);
  void jcc(Condition cc, Label& L, 
           relocInfo::relocType rtype = relocInfo::none);

  // Force an 8-bit jump offset
  void jccb(Condition cc, address dst);

  // Conditional jump to a 8-bit offset to L.
  // WARNING: be very careful using this for forward jumps.  If the label is
  // not bound within an 8-bit offset of this instruction, a run-time error
  // will occur.
  void jccb(Condition cc, Label& L);

  // Floating-point operations

  void fxsave(Address dst);
  void fxrstor(Address src);
  void ldmxcsr(Address src);
  void stmxcsr(Address dst);

  void addss(FloatRegister dst, FloatRegister src);
  void addss(FloatRegister dst, Address src);
  void subss(FloatRegister dst, FloatRegister src);
  void subss(FloatRegister dst, Address src);
  void mulss(FloatRegister dst, FloatRegister src);
  void mulss(FloatRegister dst, Address src);
  void divss(FloatRegister dst, FloatRegister src);
  void divss(FloatRegister dst, Address src);
  void addsd(FloatRegister dst, FloatRegister src);
  void addsd(FloatRegister dst, Address src);
  void subsd(FloatRegister dst, FloatRegister src);
  void subsd(FloatRegister dst, Address src);
  void mulsd(FloatRegister dst, FloatRegister src);
  void mulsd(FloatRegister dst, Address src);
  void divsd(FloatRegister dst, FloatRegister src);
  void divsd(FloatRegister dst, Address src);

  // We only need the double form
  void sqrtsd(FloatRegister dst, FloatRegister src);
  void sqrtsd(FloatRegister dst, Address src);

  void xorps(FloatRegister dst, FloatRegister src);
  void xorps(FloatRegister dst, Address src);
  void xorpd(FloatRegister dst, FloatRegister src);
  void xorpd(FloatRegister dst, Address src);

  void cvtsi2ssl(FloatRegister dst, Register src);
  void cvtsi2ssq(FloatRegister dst, Register src);
  void cvtsi2sdl(FloatRegister dst, Register src);
  void cvtsi2sdq(FloatRegister dst, Register src);
  void cvttss2sil(Register dst, FloatRegister src); // truncates
  void cvttss2siq(Register dst, FloatRegister src); // truncates
  void cvttsd2sil(Register dst, FloatRegister src); // truncates
  void cvttsd2siq(Register dst, FloatRegister src); // truncates
  void cvtss2sd(FloatRegister dst, FloatRegister src);
  void cvtsd2ss(FloatRegister dst, FloatRegister src);
};


// MacroAssembler extends Assembler by frequently used macros.
//
// Instructions for which a 'better' code sequence exists depending
// on arguments should also go in here.

class MacroAssembler
  : public Assembler 
{
 protected:
  // Support for VM calls
  //
  // This is the base routine called by the different versions of
  // call_VM_leaf. The interpreter may customize this version by
  // overriding it for its purposes (e.g., to save/restore additional
  // registers when doing a VM call).
  
  virtual void call_VM_leaf_base(
    address entry_point,               // the entry point
    int     number_of_arguments        // the number of arguments to
                                       // pop after the call
  );

  // This is the base routine called by the different versions of
  // call_VM. The interpreter may customize this version by overriding
  // it for its purposes (e.g., to save/restore additional registers
  // when doing a VM call).
  //
  // If no java_thread register is specified (noreg) than rdi will be
  // used instead. call_VM_base returns the register which contains
  // the thread upon return. If a thread register has been specified,
  // the return value will correspond to that register. If no
  // last_java_sp is specified (noreg) than rsp will be used instead.
  virtual void call_VM_base(           // returns the register
                                       // containing the thread upon
                                       // return
    Register oop_result,               // where an oop-result ends up
                                       // if any; use noreg otherwise
    Register java_thread,              // the thread if computed
                                       // before ; use noreg otherwise
    Register last_java_sp,             // to set up last_Java_frame in
                                       // stubs; use noreg otherwise
    address  entry_point,              // the entry point
    int      number_of_arguments,      // the number of arguments (w/o
                                       // thread) to pop after the
                                       // call
    bool     check_exceptions          // whether to check for pending
                                       // exceptions after return
  ); 

  // This routines should emit JVMTI PopFrame handling and ForceEarlyReturn code.
  // The implementation is only non-empty for the InterpreterMacroAssembler,
  // as only the interpreter handles PopFrame and ForceEarlyReturn requests.
  virtual void check_and_handle_popframe(Register java_thread);
  virtual void check_and_handle_earlyret(Register java_thread);

  void call_VM_helper(Register oop_result, 
                      address entry_point, 
                      int number_of_arguments, 
                      bool check_exceptions = true);
  
 public:
  MacroAssembler(CodeBuffer* code) : Assembler(code) {}

  // Support for NULL-checks
  //
  // Generates code that causes a NULL OS exception if the content of
  // reg is NULL.  If the accessed location is M[reg + offset] and the
  // offset is known, provide the offset. No explicit code generation
  // is needed if the offset is within a certain range (0 <= offset <=
  // page_size).
  void null_check(Register reg, int offset = -1);
  static bool needs_explicit_null_check(int offset);

  // Required platform-specific helpers for Label::patch_instructions.
  // They _shadow_ the declarations in AbstractAssembler, which are undefined.
  void pd_patch_instruction(address branch, address target);
#ifndef PRODUCT
  static void pd_print_patched_instruction(address branch);
#endif


  // The following 4 methods return the offset of the appropriate move
  // instruction.  Note: these are 32 bit instructions

  // Support for fast byte/word loading with zero extension (depending
  // on particular CPU)
  int load_unsigned_byte(Register dst, Address src);
  int load_unsigned_word(Register dst, Address src);

  // Support for fast byte/word loading with sign extension (depending
  // on particular CPU)
  int load_signed_byte(Register dst, Address src);
  int load_signed_word(Register dst, Address src);

  // Support for inc/dec with optimal instruction selection depending
  // on value
  void incrementl(Register reg, int value = 1);
  void decrementl(Register reg, int value = 1);
  void incrementq(Register reg, int value = 1);
  void decrementq(Register reg, int value = 1);

  void incrementl(Address dst, int value = 1);
  void decrementl(Address dst, int value = 1);
  void incrementq(Address dst, int value = 1);
  void decrementq(Address dst, int value = 1);

  // Support optimal SSE move instructions.
  void movflt(FloatRegister dst, FloatRegister src) {
    if (UseXmmRegToRegMoveAll) { movaps(dst, src); return; }
    else                       { movss (dst, src); return; }
  }
  void movflt(FloatRegister dst, Address src) { movss(dst, src); }
  void movflt(Address dst, FloatRegister src) { movss(dst, src); }

  void movdbl(FloatRegister dst, FloatRegister src) {
    if (UseXmmRegToRegMoveAll) { movapd(dst, src); return; }
    else                       { movsd (dst, src); return; }
  }
  void movdbl(FloatRegister dst, Address src) {
    if (UseXmmLoadAndClearUpper) { movsd (dst, src); return; }
    else                         { movlpd(dst, src); return; }
  }
  void movdbl(Address dst, FloatRegister src) { movsd(dst, src); }

  // Alignment
  void align(int modulus);

  // Misc
  void fat_nop(); // 5 byte nop 

  // If we can reach the address with a 32 bit displacement
  // return true otherwise load the address into the scratch register
  // and return false.
  inline bool check_reach(Address adr) 
  { 
    if (is_reachable(adr)) 
      return true;
    // Must make it relocatable if it was relocatable in the first place
    Assembler::movq(rscratch1, adr._target, adr.reloc());      
    return false;
  }

  void pushq(Address src)
  { 
    check_reach(src)? Assembler::pushq(src) : 
                      Assembler::pushq(Address(rscratch1)); 
  }

  void popq(Address dst)
  { 
    check_reach(dst)? Assembler::popq(dst) : 
                      Assembler::popq(Address(rscratch1)); 
  }

  // Moves
  void movb(Register dst, Address src)
  { 
    check_reach(dst) ? Assembler::movb(dst, src) :
                       Assembler::movb(dst, Address(rscratch1));
  }
  void movb(Address dst, int imm8)
  { 
    check_reach(dst) ? Assembler::movb(dst, imm8) :
                       Assembler::movb(Address(rscratch1), imm8);
  }
  void movb(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::movb(dst, src) :
                       Assembler::movb(Address(rscratch1), src);
  }

  void movw(Address dst, int imm16)
  { 
    check_reach(dst) ? Assembler::movw(dst, imm16) :
                       Assembler::movw(Address(rscratch1), imm16);
  }
  void movw(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::movw(dst, src) :
                       Assembler::movw(dst, Address(rscratch1));
  }
  void movw(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::movw(dst, src) :
                       Assembler::movw(Address(rscratch1), src);
  }

  void movl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::movl(dst, src) :
                       Assembler::movl(dst, Address(rscratch1));
  }
  void movl(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::movl(dst, imm32) :
                       Assembler::movl(Address(rscratch1), imm32);
  }
  void movl(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::movl(dst, src) :
                       Assembler::movl(Address(rscratch1), src);
  }

  void movq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::movq(dst, src) :
                       Assembler::movq(dst, Address(rscratch1));
  }
  void movq(Address dst, int64_t imm64)
  { 
    if (check_reach(dst)) {
      if (is_simm32(imm64)) {
        Assembler::movq(dst, (int) imm64);
      } else {
        Assembler::movq(rscratch1,  imm64);
        Assembler::movq(dst, rscratch1);
      }
    } else {
      if (is_simm32(imm64)) {
        Assembler::movq(Address(rscratch1), (int) imm64);
      } else {
        Assembler::movq(rscratch2,  imm64);
        Assembler::movq(Address(rscratch1), rscratch2);
      }
    }
  }
  void movq(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::movq(dst, src) :
                       Assembler::movq(Address(rscratch1), src);
  }

  void movsbl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::movsbl(dst, src) :
                       Assembler::movsbl(dst, Address(rscratch1));
  }
  void movswl(Register dst, Address src)
  { 
    check_reach(dst) ? Assembler::movswl(dst, src) :
                       Assembler::movswl(dst, Address(rscratch1));
  }
  void movslq(Register dst, Address src)
  { 
    check_reach(dst) ? Assembler::movslq(dst, src) :
                       Assembler::movslq(dst, Address(rscratch1));
  }

  void movzbl(Register dst, Address src)
  { 
    check_reach(dst) ? Assembler::movzbl(dst, src) :
                       Assembler::movzbl(dst, Address(rscratch1));
  }
  void movzwl(Register dst, Address src)
  { 
    check_reach(dst) ? Assembler::movzwl(dst, src) :
                       Assembler::movzwl(dst, Address(rscratch1));
  }

private:
  void movss(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::movss(dst, src) :
                       Assembler::movss(dst, Address(rscratch1));
  }
  void movss(Address dst, FloatRegister src)
  { 
    check_reach(dst) ? Assembler::movss(dst, src) :
                       Assembler::movss(Address(rscratch1), src);
  }
  void movlpd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::movlpd(dst, src) :
                       Assembler::movlpd(dst, Address(rscratch1));
  }
  void movsd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::movsd(dst, src) :
                       Assembler::movsd(dst, Address(rscratch1));
  }
  void movsd(Address dst, FloatRegister src)
  { 
    check_reach(dst) ? Assembler::movsd(dst, src) :
                       Assembler::movsd(Address(rscratch1), src);
  }

public:
  void cmovl(Condition cc, Register dst, Address src)
  { 
    check_reach(src) ? Assembler::cmovl(cc, dst, src) :
                       Assembler::cmovl(cc, dst, Address(rscratch1));
  }
  void cmovq(Condition cc, Register dst, Address src)
  { 
    check_reach(src) ? Assembler::cmovq(cc, dst, src) :
                       Assembler::cmovq(cc, dst, Address(rscratch1));
  }

  // Arithmetics
  void adcl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::adcl(dst, src) :
                       Assembler::adcl(dst, Address(rscratch1));
  }
  void adcq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::adcq(dst, src) :
                       Assembler::adcq(dst, Address(rscratch1));
  }

  void addl(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::addl(dst, imm32) :
                       Assembler::addl(Address(rscratch1), imm32);
  }
  void addl(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::addl(dst, src) :
                       Assembler::addl(Address(rscratch1), src);
  }
  void addl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::addl(dst, src) :
                       Assembler::addl(dst, Address(rscratch1));
  }
  void addq(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::addq(dst, imm32) :
                       Assembler::addq(Address(rscratch1), imm32);
  }
  void addq(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::addq(dst, src) :
                       Assembler::addq(Address(rscratch1), src);
  }
  void addq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::addq(dst, src) :
                       Assembler::addq(dst, Address(rscratch1));
  }

  void andl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::andl(dst, src) :
                       Assembler::andl(dst, Address(rscratch1));
  }
  void andq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::andq(dst, src) :
                       Assembler::andq(dst, Address(rscratch1));
  }

  void cmpb(Address dst, int imm8)
  { 
    check_reach(dst) ? Assembler::cmpb(dst, imm8) :
                       Assembler::cmpb(Address(rscratch1), imm8);
  }
  void cmpl(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::cmpl(dst, imm32) :
                       Assembler::cmpl(Address(rscratch1), imm32);
  }
  void cmpl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::cmpl(dst, src) :
                       Assembler::cmpl(dst, Address(rscratch1));
  }
  void cmpq(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::cmpq(dst, imm32) :
                       Assembler::cmpq(Address(rscratch1), imm32);
  }
  void cmpq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::cmpq(dst, src) :
                       Assembler::cmpq(dst, Address(rscratch1));
  }

  void decl(Address dst)
  { 
    check_reach(dst) ? Assembler::decl(dst) :
                       Assembler::decl(Address(rscratch1));
  }
  void decq(Address dst)
  { 
    check_reach(dst) ? Assembler::decq(dst) :
                       Assembler::decq(Address(rscratch1));
  }

  void incl(Address dst)
  { 
    check_reach(dst) ? Assembler::incl(dst) :
                       Assembler::incl(Address(rscratch1));
  }
  void incq(Address dst)
  { 
    check_reach(dst) ? Assembler::incq(dst) :
                       Assembler::incq(Address(rscratch1));
  }

  void leal(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::leal(dst, src) :
                       Assembler::leal(dst, Address(rscratch1));
  }
  void leaq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::leaq(dst, src) :
                       Assembler::leaq(dst, Address(rscratch1));
  }

  void mull(Address src)
  { 
    check_reach(src) ? Assembler::mull(src) :
                       Assembler::mull(Address(rscratch1));
  }

  void orl(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::orl(dst, imm32) :
                       Assembler::orl(Address(rscratch1), imm32);
  }
  void orl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::orl(dst, src) :
                       Assembler::orl(dst, Address(rscratch1));
  }
  void orq(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::orq(dst, imm32) :
                       Assembler::orq(Address(rscratch1), imm32);
  }
  void orq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::orq(dst, src) :
                       Assembler::orq(dst, Address(rscratch1));
  }
  
  void sbbl(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::sbbl(dst, imm32) :
                       Assembler::sbbl(Address(rscratch1), imm32);
  }
  void sbbl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::sbbl(dst, src) :
                       Assembler::sbbl(dst, Address(rscratch1));
  }
  void sbbq(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::sbbq(dst, imm32) :
                       Assembler::sbbq(Address(rscratch1), imm32);
  }
  void sbbq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::sbbq(dst, src) :
                       Assembler::sbbq(dst, Address(rscratch1));
  }

  void subl(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::subl(dst, imm32) :
                       Assembler::subl(Address(rscratch1), imm32);
  }
  void subl(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::subl(dst, src) :
                       Assembler::subl(Address(rscratch1), src);
  }
  void subl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::subl(dst, src) :
                       Assembler::subl(dst, Address(rscratch1));
  }
  void subq(Address dst, int imm32)
  { 
    check_reach(dst) ? Assembler::subq(dst, imm32) :
                       Assembler::subq(Address(rscratch1), imm32);
  }
  void subq(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::subq(dst, src) :
                       Assembler::subq(Address(rscratch1), src);
  }
  void subq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::subq(dst, src) :
                       Assembler::subq(dst, Address(rscratch1));
  }

  void xaddl(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::xaddl(dst, src) :
                       Assembler::xaddl(Address(rscratch1), src);
  }
  void xaddq(Address dst, Register src)
  { 
    check_reach(dst) ? Assembler::xaddq(dst, src) :
                       Assembler::xaddq(Address(rscratch1), src);
  }

  void xorl(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::xorl(dst, src) :
                       Assembler::xorl(dst, Address(rscratch1));
  }
  void xorq(Register dst, Address src)
  { 
    check_reach(src) ? Assembler::xorq(dst, src) :
                       Assembler::xorq(dst, Address(rscratch1));
  }

  void xchgl(Register reg, Address adr)
  { 
    check_reach(adr) ? Assembler::xchgl(reg, adr) :
                       Assembler::xchgl(reg, Address(rscratch1));
  }
  void xchgq(Register reg, Address adr)
  { 
    check_reach(adr) ? Assembler::xchgq(reg, adr) :
                       Assembler::xchgq(reg, Address(rscratch1));
  }

  void cmpxchgl(Register reg, Address adr)
  { 
    check_reach(adr) ? Assembler::cmpxchgl(reg, adr) :
                       Assembler::cmpxchgl(reg, Address(rscratch1));
  }
  void cmpxchgq(Register reg, Address adr)
  { 
    check_reach(adr) ? Assembler::cmpxchgq(reg, adr) :
                       Assembler::cmpxchgq(reg, Address(rscratch1));
  }

  // Calls
  void call(Label& L, relocInfo::relocType rtype)
  { 
    Assembler::call(L, rtype);
  }

  void call(address entry, relocInfo::relocType rtype)
  { 
    Address dest(entry, rtype);
    check_reach(dest) ? Assembler::call(entry, rtype) :
                        Assembler::call(rscratch1, relocInfo::none);
  }
  void call(address entry, RelocationHolder const& rspec)
  { 
    Address dest(entry, rspec);
    check_reach(dest) ? Assembler::call(entry, rspec) :
                        Assembler::call(rscratch1, relocInfo::none);
  }
  void call(Address adr)
  { 
    check_reach(adr) ? Assembler::call(adr) :
                       Assembler::call(rscratch1, relocInfo::none);
  }
  
  // Jumps
  void jmp(address entry, relocInfo::relocType rtype)
  { 
    Address dest(entry, rtype);
    check_reach(dest) ? Assembler::jmp(entry, rtype) :
                        Assembler::jmp(rscratch1, relocInfo::none);
  }
  void jmp(Address adr)
  { 
    check_reach(adr) ? Assembler::jmp(adr) :
                       Assembler::jmp(rscratch1);
  }

  // Label operations & relative jumps (PPUM Appendix D)
  // unconditional jump to L
  void jmp(Label& L, relocInfo::relocType rtype = relocInfo::none) 
  { 
    Assembler::jmp(L, rtype);
  }

  void jcc(Condition cc, address dst, 
           relocInfo::relocType rtype = relocInfo::runtime_call_type);

  void jcc(Condition cc, Label& L, 
           relocInfo::relocType rtype = relocInfo::none)
  { 
    Assembler::jcc(cc, L, rtype);
  }


  // Floating-point operations

  void fxsave(Address dst)
  { 
    check_reach(dst) ? Assembler::fxsave(dst) :
                       Assembler::fxsave(Address(rscratch1));
  }
  void fxrstor(Address src)
  { 
    check_reach(src) ? Assembler::fxrstor(src) :
                       Assembler::fxrstor(Address(rscratch1));
  }
  void ldmxcsr(Address src)
  { 
    check_reach(src) ? Assembler::ldmxcsr(src) :
                       Assembler::ldmxcsr(Address(rscratch1));
  }
  void stmxcsr(Address dst)
  { 
    check_reach(dst) ? Assembler::stmxcsr(dst) :
                       Assembler::stmxcsr(Address(rscratch1));
  }

  void addss(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::addss(dst, src) :
                       Assembler::addss(dst, Address(rscratch1));
  }
  void subss(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::subss(dst, src) :
                       Assembler::subss(dst, Address(rscratch1));
  }
  void mulss(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::mulss(dst, src) :
                       Assembler::mulss(dst, Address(rscratch1));
  }
  void divss(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::divss(dst, src) :
                       Assembler::divss(dst, Address(rscratch1));
  }
  void addsd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::addsd(dst, src) :
                       Assembler::addsd(dst, Address(rscratch1));
  }
  void subsd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::subsd(dst, src) :
                       Assembler::subsd(dst, Address(rscratch1));
  }
  void mulsd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::mulsd(dst, src) :
                       Assembler::mulsd(dst, Address(rscratch1));
  }
  void divsd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::divsd(dst, src) :
                       Assembler::divsd(dst, Address(rscratch1));
  }

  // We only need the double form
  void sqrtsd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::sqrtsd(dst, src) :
                       Assembler::sqrtsd(dst, Address(rscratch1));
  }

  void xorps(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::xorps(dst, src) :
                       Assembler::xorps(dst, Address(rscratch1));
  }
  void xorpd(FloatRegister dst, Address src)
  { 
    check_reach(src) ? Assembler::xorpd(dst, src) :
                       Assembler::xorpd(dst, Address(rscratch1));
  }

  // Entrypoints which only call to the super class.
  // Needed here to satisfy the C++ compiler
  // Since we are overriding some of the methods which take
  // an Address as an argument.

  void pushq(int imm32)                     { Assembler::pushq(imm32); }
  void pushq(Register src)                  { Assembler::pushq(src); }
  void popq(Register dst)                   { Assembler::popq(dst); }
  void movl(Register dst, int imm32)        { Assembler::movl(dst, imm32); }
  void movl(Register dst, Register src)     { Assembler::movl(dst, src); }
  void movq(Register dst, int64_t imm64)    { Assembler::movq(dst, imm64); }
  void movq(Register dst, address imm64, relocInfo::relocType rtype) { Assembler::movq(dst, imm64, rtype); }
  void movq(Register dst, Register src)     { Assembler::movq(dst, src); }
  void movq(Register dst, jobject obj)      { Assembler::movq(dst, obj); }
  void movsbl(Register dst, Register src)   { Assembler::movsbl(dst, src); }
  void movswl(Register dst, Register src)   { Assembler::movswl(dst, src); }
  void movslq(Register dst, Register src)   { Assembler::movslq(dst, src); }
  void movzbl(Register dst, Register src)   { Assembler::movzbl(dst, src); }
  void movzwl(Register dst, Register src)   { Assembler::movzwl(dst, src); }
private:
  void movss(FloatRegister dst, FloatRegister src) { Assembler::movss(dst, src); }
  void movsd(FloatRegister dst, FloatRegister src) { Assembler::movsd(dst, src); }

public:
  void movdl(FloatRegister dst, Register src) { Assembler::movdl(dst, src); }
  void movdl(Register dst, FloatRegister src) { Assembler::movdl(dst, src); }
  void movdq(FloatRegister dst, Register src) { Assembler::movdq(dst, src); }
  void movdq(Register dst, FloatRegister src) { Assembler::movdq(dst, src); }
  void cmovl(Condition cc, Register dst, Register src) { Assembler::cmovl(cc, dst, src); }
  void cmovq(Condition cc, Register dst, Register src) { Assembler::cmovq(cc, dst, src); }
  void adcl(Register dst, int imm32)        { Assembler::adcl(dst, imm32); }
  void adcl(Register dst, Register src)     { Assembler::adcl(dst, src); }
  void adcq(Register dst, int imm32)        { Assembler::adcq(dst, imm32); }
  void adcq(Register dst, Register src)     { Assembler::adcq(dst, src); }
  void addl(Register dst, int imm32)        { Assembler::addl(dst, imm32); }
  void addl(Register dst, Register src)     { Assembler::addl(dst, src); }
  void addq(Register dst, int imm32)        { Assembler::addq(dst, imm32); }
  void addq(Register dst, Register src)     { Assembler::addq(dst, src); }
  void andl(Register dst, int imm32)        { Assembler::andl(dst, imm32); }
  void andl(Register dst, Register src)     { Assembler::andl(dst, src); }
  void andq(Register dst, int imm32)        { Assembler::andq(dst, imm32); }
  void andq(Register dst, Register src)     { Assembler::andq(dst, src); }
  void cmpl(Register dst, int imm32)        { Assembler::cmpl(dst, imm32); }
  void cmpl(Register dst, Register src)     { Assembler::cmpl(dst, src); }
  void cmpq(Register dst, int imm32)        { Assembler::cmpq(dst, imm32); }
  void cmpq(Register dst, Register src)     { Assembler::cmpq(dst, src); }
  void ucomiss(FloatRegister dst, FloatRegister src) { Assembler::ucomiss(dst, src); }
  void ucomisd(FloatRegister dst, FloatRegister src) { Assembler::ucomisd(dst, src); }
  void decl(Register dst)                   { Assembler::decl(dst); }
  void decq(Register dst)                   { Assembler::decq(dst); }
  void idivl(Register src)                  { Assembler::idivl(src); }
  void idivq(Register src)                  { Assembler::idivq(src); }
  void imull(Register dst, Register src)    { Assembler::imull(dst, src); }
  void imull(Register dst, Register src, int value) { Assembler::imull(dst, src, value); }
  void imulq(Register dst, Register src)    { Assembler::imulq(dst, src); }
  void imulq(Register dst, Register src, int value) { Assembler::imulq(dst, src, value); }
  void incl(Register dst)                   { Assembler::incl(dst); }
  void incq(Register dst)                   { Assembler::incq(dst); }
  void mull(Register src)                   { Assembler::mull(src); }
  void negl(Register dst)                   { Assembler::negl(dst); }
  void negq(Register dst)                   { Assembler::negq(dst); }
  void notl(Register dst)                   { Assembler::notl(dst); }
  void notq(Register dst)                   { Assembler::notq(dst); }
  void orl(Register dst, int imm32)         { Assembler::orl(dst, imm32); }
  void orl(Register dst, Register src)      { Assembler::orl(dst, src); }
  void orq(Register dst, int imm32)         { Assembler::orq(dst, imm32); }
  void orq(Register dst, Register src)      { Assembler::orq(dst, src); }
  void rcll(Register dst, int imm8)         { Assembler::rcll(dst, imm8); }
  void rclq(Register dst, int imm8)         { Assembler::rclq(dst, imm8); }
  void sarl(Register dst, int imm8)         { Assembler::sarl(dst, imm8); }
  void sarl(Register dst)                   { Assembler::sarl(dst); }
  void sarq(Register dst, int imm8)         { Assembler::sarq(dst, imm8); }
  void sarq(Register dst)                   { Assembler::sarq(dst); }
  void sbbl(Register dst, int imm32)        { Assembler::sbbl(dst, imm32); }
  void sbbl(Register dst, Register src)     { Assembler::sbbl(dst, src); }
  void sbbq(Register dst, int imm32)        { Assembler::sbbq(dst, imm32); }
  void sbbq(Register dst, Register src)     { Assembler::sbbq(dst, src); }
  void shll(Register dst, int imm8)         { Assembler::shll(dst, imm8); }
  void shll(Register dst)                   { Assembler::shll(dst); }
  void shlq(Register dst, int imm8)         { Assembler::shlq(dst, imm8); }
  void shlq(Register dst)                   { Assembler::shlq(dst); }
  void shrl(Register dst, int imm8)         { Assembler::shrl(dst, imm8); }
  void shrl(Register dst)                   { Assembler::shrl(dst); }
  void shrq(Register dst, int imm8)         { Assembler::shrq(dst, imm8); }
  void shrq(Register dst)                   { Assembler::shrq(dst); }
  void subl(Register dst, int imm32)        { Assembler::subl(dst, imm32); }
  void subl(Register dst, Register src)     { Assembler::subl(dst, src); }
  void subq(Register dst, int imm32)        { Assembler::subq(dst, imm32); }
  void subq(Register dst, Register src)     { Assembler::subq(dst, src); }
  void testb(Register dst, int imm8)        { Assembler::testb(dst, imm8); }
  void testl(Register dst, int imm32)       { Assembler::testl(dst, imm32); }
  void testl(Register dst, Register src)    { Assembler::testl(dst, src); }
  void testq(Register dst, int imm32)       { Assembler::testq(dst, imm32); }
  void testq(Register dst, Register src)    { Assembler::testq(dst, src); }
  void xorl(Register dst, int imm32)        { Assembler::xorl(dst, imm32); }
  void xorl(Register dst, Register src)     { Assembler::xorl(dst, src); }
  void xorq(Register dst, int imm32)        { Assembler::xorq(dst, imm32); }
  void xorq(Register dst, Register src)     { Assembler::xorq(dst, src); }
  void bswapl(Register reg)                 { Assembler::bswapl(reg); }
  void bswapq(Register reg)                 { Assembler::bswapq(reg); }
  void xchgl(Register dst, Register src)    { Assembler::xchgl(dst, src); }
  void xchgq(Register dst, Register src)    { Assembler::xchgq(dst, src); }
  void call(Register reg,  relocInfo::relocType rtype) { Assembler::call(reg, rtype); }
  void jmp(Register reg,  relocInfo::relocType rtype = relocInfo::none) { Assembler::jmp(reg, rtype); }
  void addss(FloatRegister dst, FloatRegister src) { Assembler::addss(dst, src); }
  void subss(FloatRegister dst, FloatRegister src) { Assembler::subss(dst, src); }
  void mulss(FloatRegister dst, FloatRegister src) { Assembler::mulss(dst, src); }
  void divss(FloatRegister dst, FloatRegister src) { Assembler::divss(dst, src); }
  void addsd(FloatRegister dst, FloatRegister src) { Assembler::addsd(dst, src); }
  void subsd(FloatRegister dst, FloatRegister src) { Assembler::subsd(dst, src); }
  void mulsd(FloatRegister dst, FloatRegister src) { Assembler::mulsd(dst, src); }
  void divsd(FloatRegister dst, FloatRegister src) { Assembler::divsd(dst, src); }
  void sqrtsd(FloatRegister dst, FloatRegister src) { Assembler::sqrtsd(dst, src); }
  void xorps(FloatRegister dst, FloatRegister src) { Assembler::xorps(dst, src); }
  void xorpd(FloatRegister dst, FloatRegister src) { Assembler::xorpd(dst, src); }
  void cvtsi2ssl(FloatRegister dst, Register src) { Assembler::cvtsi2ssl(dst, src); }
  void cvtsi2ssq(FloatRegister dst, Register src) { Assembler::cvtsi2ssq(dst, src); }
  void cvtsi2sdl(FloatRegister dst, Register src) { Assembler::cvtsi2sdl(dst, src); }
  void cvtsi2sdq(FloatRegister dst, Register src) { Assembler::cvtsi2sdq(dst, src); }
  void cvttss2sil(Register dst, FloatRegister src) { Assembler::cvttss2sil(dst, src); }
  void cvttss2siq(Register dst, FloatRegister src) { Assembler::cvttss2siq(dst, src); }
  void cvttsd2sil(Register dst, FloatRegister src) { Assembler::cvttsd2sil(dst, src); }
  void cvttsd2siq(Register dst, FloatRegister src) { Assembler::cvttsd2siq(dst, src); }
  void cvtss2sd(FloatRegister dst, FloatRegister src) { Assembler::cvtss2sd(dst, src); }
  void cvtsd2ss(FloatRegister dst, FloatRegister src) { Assembler::cvtsd2ss(dst, src); }

  // C++ bool manipulation

  void movbool(Register dst, Address src);
  void movbool(Address dst, bool boolconst);
  void movbool(Address dst, Register src);
  void testbool(Register dst);

  // Stack frame creation/removal
  void enter();
  void leave();

  // Support for getting the JavaThread pointer (i.e.; a reference to
  // thread-local information) The pointer will be loaded into the
  // thread register.
  void get_thread(Register thread);

  // Support for VM calls
  //
  // It is imperative that all calls into the VM are handled via the
  // call_VM macros.  They make sure that the stack linkage is setup
  // correctly. call_VM's correspond to ENTRY/ENTRY_X entry points
  // while call_VM_leaf's correspond to LEAF entry points.
  void call_VM(Register oop_result, 
               address entry_point, 
               bool check_exceptions = true);
  void call_VM(Register oop_result, 
               address entry_point, 
               Register arg_1, 
               bool check_exceptions = true);
  void call_VM(Register oop_result,
               address entry_point,
               Register arg_1, Register arg_2,
               bool check_exceptions = true);
  void call_VM(Register oop_result,
               address entry_point, 
               Register arg_1, Register arg_2, Register arg_3,
               bool check_exceptions = true);

  // Overloadings with last_Java_sp
  void call_VM(Register oop_result,
               Register last_java_sp, 
               address entry_point, 
               int number_of_arguments = 0,
               bool check_exceptions = true);
  void call_VM(Register oop_result,
               Register last_java_sp,
               address entry_point,
               Register arg_1, bool
               check_exceptions = true);
  void call_VM(Register oop_result,
               Register last_java_sp,
               address entry_point,
               Register arg_1, Register arg_2,
               bool check_exceptions = true);
  void call_VM(Register oop_result,
               Register last_java_sp,
               address entry_point,
               Register arg_1, Register arg_2, Register arg_3,
               bool check_exceptions = true);

  void call_VM_leaf(address entry_point,
                    int number_of_arguments = 0);
  void call_VM_leaf(address entry_point,
                    Register arg_1);
  void call_VM_leaf(address entry_point,
                    Register arg_1, Register arg_2);
  void call_VM_leaf(address entry_point,
                    Register arg_1, Register arg_2, Register arg_3);

  // last Java Frame (fills frame anchor)
  void set_last_Java_frame(Register last_java_sp,
                           Register last_java_fp,
                           address last_java_pc);
  void reset_last_Java_frame(bool clear_fp, bool clear_pc);

  // Stores
  void store_check(Register obj);                // store check for
                                                 // obj - register is
                                                 // destroyed
                                                 // afterwards
  void store_check(Register obj, Address dst);   // same as above, dst
                                                 // is exact store
                                                 // location (reg. is
                                                 // destroyed)

  // split store_check(Register obj) to enhance instruction interleaving
  void store_check_part_1(Register obj);
  void store_check_part_2(Register obj);

  // C 'boolean' to Java boolean: x == 0 ? 0 : 1
  void c2bool(Register x);

  // Int division/reminder for Java
  // (as idivl, but checks for special case as described in JVM spec.)
  // returns idivl instruction offset for implicit exception handling
  int corrected_idivl(Register reg);
  // Long division/reminder for Java
  // (as idivq, but checks for special case as described in JVM spec.)
  // returns idivq instruction offset for implicit exception handling
  int corrected_idivq(Register reg);

  // Push and pop integer/fpu/cpu state
  void push_IU_state();
  void pop_IU_state();

  void push_FPU_state();
  void pop_FPU_state();

  void push_CPU_state();
  void pop_CPU_state();

  // Sign extension
  void sign_extend_short(Register reg);
  void sign_extend_byte(Register reg);

  // Division by power of 2, rounding towards 0
  void division_with_shift(Register reg, int shift_value);

  // Round up to a power of two
  void round_to_l(Register reg, int modulus);
  void round_to_q(Register reg, int modulus);

  // allocation
  void eden_allocate(
    Register obj,               // result: pointer to object after
                                // successful allocation
    Register var_size_in_bytes, // object size in bytes if unknown at
                                // compile time; invalid otherwise
    int con_size_in_bytes,      // object size in bytes if known at
                                // compile time
    Register t1,                // temp register
    Label& slow_case            // continuation point if fast
                                // allocation fails
    );
  void tlab_allocate(
    Register obj,               // result: pointer to object after
                                // successful allocation
    Register var_size_in_bytes, // object size in bytes if unknown at
                                // compile time; invalid otherwise
    int con_size_in_bytes,      // object size in bytes if known at
                                // compile time
    Register t1,                // temp register
    Register t2,                // temp register
    Label& slow_case            // continuation point if fast
                                // allocation fails
  );
  void tlab_refill(Label& retry_tlab, Label& try_eden, Label& slow_case);

  //----

  // Debugging

  // only if +VerifyOops
  void verify_oop(Register reg, const char* s = "broken oop"); 
  void verify_oop_addr(Address addr, const char * s = "broken oop addr");

  // only if +VerifyFPU
  void verify_FPU(int stack_depth, const char* s = "illegal FPU state") {}

  // prints msg, dumps registers and stops execution
  void stop(const char* msg); 

  // prints message and continues
  void warn(const char* msg); 

  static void debug(char* msg, int64_t pc, int64_t regs[]);

  void os_breakpoint();

  void untested()
  { 
    stop("untested");
  }

  void unimplemented(const char* what = "") 
  { 
    char* b = new char[1024];  
    sprintf(b, "unimplemented: %s", what);
    stop(b); 
  }

  void should_not_reach_here()
  { 
    stop("should not reach here");
  }

  // Stack overflow checking
  void bang_stack_with_offset(int offset)
  {
    // stack grows down, caller passes positive offset
    assert(offset > 0, "must bang with negative offset");
    movl(Address(rsp, (-offset)), rax);
  }

  // Writes to stack successive pages until offset reached to check for
  // stack overflow + shadow pages.  Also, clobbers tmp
  void bang_stack_size(Register offset, Register tmp);

  // Support for serializing memory accesses between threads.
  void serialize_memory(Register thread, Register tmp1, Register tmp2);

  void verify_tlab();

  // Biased locking support
  // lock_reg and obj_reg must be loaded up with the appropriate values.
  // swap_reg must be rax and is killed.
  // tmp_reg must be supplied and is killed.
  // If swap_reg_contains_mark is true then the code assumes that the
  // mark word of the object has already been loaded into swap_reg.
  // Optional slow case is for implementations (interpreter and C1) which branch to
  // slow case directly. Leaves condition codes set for C2's Fast_Lock node.
  // Returns offset of first potentially-faulting instruction for null
  // check info (currently consumed only by C1). If
  // swap_reg_contains_mark is true then returns -1 as it is assumed
  // the calling code has already passed any potential faults.
  int biased_locking_enter(Register lock_reg, Register obj_reg, Register swap_reg, Register tmp_reg,
                           bool swap_reg_contains_mark,
                           Label& done, Label* slow_case = NULL,
                           BiasedLockingCounters* counters = NULL);
  void biased_locking_exit (Register obj_reg, Register temp_reg, Label& done);

  Condition negate_condition(Condition cond);

  // Helper functions for statistics gathering.
  // Conditionally (atomically, on MPs) increments passed counter address, preserving condition codes.
  void cond_incl(Condition cond, Address counter_addr);
  // Unconditional atomic increment.
  void atomic_incl(Address counter_addr);
};

/**
 * class SkipIfEqual:
 *
 * Instantiating this class will result in assembly code being output that will
 * jump around any code emitted between the creation of the instance and it's
 * automatic destruction at the end of a scope block, depending on the value of
 * the flag passed to the constructor, which will be checked at run-time.
 */
class SkipIfEqual {
 private:
  MacroAssembler* _masm;
  Label _label;

 public:
   SkipIfEqual(MacroAssembler*, const bool* flag_addr, bool value);
   ~SkipIfEqual();
};


#ifdef ASSERT
inline bool AbstractAssembler::pd_check_instruction_mark() { return true; }
#endif
