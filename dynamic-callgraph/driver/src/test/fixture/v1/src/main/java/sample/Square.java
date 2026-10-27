package sample;

public final class Square implements Shape {
    private final double side;

    public Square(double side) {
        this.side = side;
    }

    @Override
    public double area() {
        return side * 2; // bug: should be side * side
    }
}
