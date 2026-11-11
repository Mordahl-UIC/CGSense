package sample;

public final class Circle implements Shape {
    private final double radius;

    public Circle(double radius) {
        this.radius = Checks.positive(radius);
    }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }
}
