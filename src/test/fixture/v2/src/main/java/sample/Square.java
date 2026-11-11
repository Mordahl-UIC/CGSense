package sample;

public final class Square implements Shape {
    private final double side;

    public Square(double side) {
        this.side = side;
    }

    @Override
    public double area() {
        return Checks.squared(side);
    }
}
