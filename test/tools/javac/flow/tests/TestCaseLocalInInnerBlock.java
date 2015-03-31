public class TestCaseLocalInInnerBlock {

    @DARanges({
        @DARange(varName="fm", bytecodeStart=23, bytecodeLength=10),
        @DARange(varName="newWidth", bytecodeStart=2, bytecodeLength=33),
        @DARange(varName="tc", bytecodeStart=5, bytecodeLength=30)
    })
    int m() {
        int newWidth = 0;
        String tc = "b";
        if (tc != null) {
            String fm;
            if (tc.trim() != null) {
            } else if ((fm = "b") != null) {
                newWidth += fm.length();
            }
        }
        return newWidth;
    }
}
