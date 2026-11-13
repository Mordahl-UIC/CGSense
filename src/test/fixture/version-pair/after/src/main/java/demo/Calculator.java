package demo;

public class Calculator {
    public int calculate() {
        return afterCalculation();
    }

    private int afterCalculation() {
        return normalize(8);
    }

    private int normalize(int value) {
        return value / 2;
    }
}
