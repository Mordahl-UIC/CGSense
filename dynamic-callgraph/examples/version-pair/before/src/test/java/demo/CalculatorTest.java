package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalculatorTest {
    @Test
    void calculatesValue() {
        Calculator calculator = new Calculator();
        assertEquals(4, calculator.calculate());
    }
}
