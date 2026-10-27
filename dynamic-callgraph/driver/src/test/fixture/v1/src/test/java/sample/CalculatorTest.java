package sample;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

public class CalculatorTest {
    private Calculator calculator;

    @Before
    public void setUp() {
        calculator = new Calculator();
    }

    @Test
    public void totalOfCircles() {
        double total = calculator.total(Arrays.<Shape>asList(new Circle(1), new Circle(2)));
        assertEquals(5 * Math.PI, total, 1e-9);
    }

    @Test
    public void squareArea() {
        assertEquals(9, new Square(3).area(), 1e-9);
    }

    @Test
    public void safeAreaFallsBack() {
        assertEquals(0, calculator.safeArea(null), 1e-9);
    }

    @Test
    public void deepThrowFallsBack() {
        assertEquals(0, calculator.deepThrow(3), 1e-9);
    }

    @Test
    public void areaInThread() throws InterruptedException {
        assertEquals(Math.PI, calculator.inThread(new Circle(1)), 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeRadiusRejected() {
        new Circle(-1);
    }
}
