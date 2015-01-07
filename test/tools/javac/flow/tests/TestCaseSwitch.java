public class TestCaseSwitch {

    @DARanges({
        @DARange(varName="o", bytecodeStart=31, bytecodeLength=16),
        @DARange(varName="o", bytecodeStart=50, bytecodeLength=15),
        @DARange(varName="o", bytecodeStart=68, bytecodeLength=1),
        @DARange(varName="oo", bytecodeStart=39, bytecodeLength=8),
        @DARange(varName="uu", bytecodeStart=59, bytecodeLength=6)
    })
    void m1(String[] args) {
        Object o;
        switch (args.length) {
            case 0:
                    o = "0";
                    o.hashCode();
                    Object oo = "oo";
                    oo.hashCode();
                    break;
            case 1:
                    o = "1";
                    o.hashCode();
                    Object uu = "uu";
                    uu.hashCode();
                    break;
        }
        o = "return";
    }

    @DARanges({
        @DARange(varName="o", bytecodeStart=35, bytecodeLength=8),
        @DARange(varName="o", bytecodeStart=46, bytecodeLength=8),
        @DARange(varName="o", bytecodeStart=78, bytecodeLength=5),
        @DARange(varName="o", bytecodeStart=86, bytecodeLength=1),
        @DARange(varName="oo", bytecodeStart=56, bytecodeLength=16)
    })
    void m3(int i) {
        Object o;
        switch (i) {
            case 0:
                    o = "0";
                    o.hashCode();
                    break;
            case 1:
                    o = "1";
                    o.hashCode();
                    break;
            case 2:
                int oo = i;
                if (oo > 1) {
                    System.out.println("greater");
                }
                break;
            case 3:
                int uu = i;
            default:
                    o = "default";
                    o.hashCode();
        }
        o = "finish";
    }

    @DARanges({
        @DARange(varName="oCache", bytecodeStart=30, bytecodeLength=6),
        @DARange(varName="cache", bytecodeStart=41, bytecodeLength=3),
        @DARange(varName="cache", bytecodeStart=54, bytecodeLength=2),
        @DARange(varName="service", bytecodeStart=39, bytecodeLength=5)
    })
    public Object m(int i) {
        Object cache;
        switch (i) {
            case 0:
                Object oCache = null;
                if (oCache != null) {
                    return oCache;
                }
            case 1:
                Object service = null;
                cache = null;
                break;
            default:
                throw new AssertionError("");
            }
        return cache;
    }

}
