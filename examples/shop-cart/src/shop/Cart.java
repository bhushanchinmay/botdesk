package shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A shopping cart. Prices are in cents to avoid floating-point money errors. */
public class Cart {

    private static final Map<String, Integer> DISCOUNT_CODES = Map.of("SAVE10", 10, "SAVE25", 25);

    private record Line(String name, long priceCents, int quantity) {}

    private final List<Line> lines = new ArrayList<>();
    private int discountPercent = 0;

    public void addItem(String name, long priceCents, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (priceCents < 0) throw new IllegalArgumentException("Price cannot be negative");
        lines.add(new Line(name, priceCents, quantity));
    }

    public void applyDiscountCode(String code) {
        Integer percent = DISCOUNT_CODES.get(code);
        if (percent == null) throw new IllegalArgumentException("Unknown discount code: " + code);
        discountPercent = percent;
    }

    public int itemCount() {
        int count = 0;
        for (Line line : lines) count += line.quantity();
        return count;
    }

    /** Total in cents after any discount. */
    public long totalCents() {
        long subtotal = 0;
        for (Line line : lines) {
            long lineTotal = line.priceCents() * line.quantity();
            subtotal += lineTotal - lineTotal * discountPercent / 100;
        }
        return subtotal - subtotal * discountPercent / 100;
    }
}
