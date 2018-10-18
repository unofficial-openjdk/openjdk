import org.openjdk.benchmarks.cont.OneShot;

public class Driver {
    public static void main(String[] args) {
        OneShot bm = new OneShot();
        bm.paramCount = 3;
        bm.stackDepth = 5;

        int n = 1000_000;

        System.out.println("Running " + n + " iterations");
        bm.setup();
        for (int i=0; i<n; i++) {
            bm.yield();
        }
        System.out.println("Done");
    }
}