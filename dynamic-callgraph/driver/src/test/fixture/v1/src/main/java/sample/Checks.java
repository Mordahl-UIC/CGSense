package sample;

final class Checks {
    private Checks() {
    }

    static double positive(double value) {
        if (value <= 0) {
            throw new IllegalArgumentException("not positive: " + value);
        }
        return value;
    }
}
