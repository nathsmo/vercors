public class MultiDimArray {
    public static void method(int[] x) {

    }

    public static void main(String[] args) {
        int[][][] a = new int[][][] {
                null,
                {null},
                {{1, 2}}
        };

        boolean cond = true;

        Object[][] x = cond ? new Object[][]{null} : new Object[][]{{null}};

        method(null);

        x[0][0] = cond ? null : null;
        x[0] = cond ? null : null;
    }
}