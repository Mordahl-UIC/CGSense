package sample;

import java.util.List;

public class Calculator {
    static final double SCALE = initScale();

    private static double initScale() {
        return 1.0;
    }

    public double total(List<Shape> shapes) {
        return shapes.stream().mapToDouble(s -> s.area() * SCALE).sum();
    }

    /** riskyArea throws and safeArea catches: the next call must be attributed to safeArea. */
    public double safeArea(Shape shape) {
        try {
            return riskyArea(shape);
        } catch (IllegalStateException e) {
            return fallback();
        }
    }

    double riskyArea(Shape shape) {
        if (shape == null) {
            throw new IllegalStateException("no shape");
        }
        return shape.area();
    }

    /** The exception crosses several frames before it is caught here. */
    public double deepThrow(int levels) {
        try {
            return descend(levels);
        } catch (IllegalStateException e) {
            return fallback();
        }
    }

    private double descend(int levels) {
        if (levels == 0) {
            return riskyArea(null);
        }
        return descend(levels - 1);
    }

    double fallback() {
        return 0;
    }

    public double inThread(Shape shape) throws InterruptedException {
        double[] result = new double[1];
        Thread worker = new Thread(() -> result[0] = shape.area());
        worker.start();
        worker.join();
        return result[0];
    }
}
