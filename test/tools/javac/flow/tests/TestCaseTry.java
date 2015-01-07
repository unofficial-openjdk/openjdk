public class TestCaseTry {

    @DARanges({
        @DARange(varName="o", bytecodeStart=3, bytecodeLength=8),
        @DARange(varName="o", bytecodeStart=15, bytecodeLength=1)
    })
    void m0(String[] args) {
        Object o;
        try {
            o = "";
            o.hashCode();
        } catch (RuntimeException e) {}
        o = "";
    }

    @DARanges({
        @DARange(varName="o", bytecodeStart=3, bytecodeLength=16),
        @DARange(varName="o", bytecodeStart=23, bytecodeLength=8),
        @DARange(varName="o", bytecodeStart=35, bytecodeLength=11)
    })
    void m1() {
        Object o;
        try {
            o = "";
            o.hashCode();
        } catch (RuntimeException e) {
        }
        finally {
            o = "finally";
            o.hashCode();
        }
        o = "";
    }

    @DARanges({
        @DARange(varName="o", bytecodeStart=3, bytecodeLength=16),
        @DARange(varName="o", bytecodeStart=23, bytecodeLength=16),
        @DARange(varName="o", bytecodeStart=43, bytecodeLength=11)
    })
    void m2() {
        Object o;
        try {
            o = "";
            o.hashCode();
        } catch (RuntimeException e) {
            o = "catch";
            o.hashCode();
        }
        finally {
            o = "finally";
            o.hashCode();
        }
        o = "";
    }
}
