public class TestCaseConditional {
    @DARanges({
        @DARange(varName="o", bytecodeStart=5, bytecodeLength=33),
        @DARange(varName="oo", bytecodeStart=23, bytecodeLength=15)
    })
    void m(String[] args) {
        Boolean o;
        Boolean oo = ((o = Boolean.TRUE).booleanValue()) ?
                o = Boolean.TRUE :
                Boolean.FALSE;
        oo.hashCode();
        o = Boolean.FALSE;
        o.hashCode();
    }
}
