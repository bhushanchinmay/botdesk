package shop;

/** Plain-Java tests: no libraries needed. Exits with code 1 if any test fails. */
public class CartTest {

    private static int failures = 0;

    public static void main(String[] args) {
        totalWithoutDiscount();
        itemCountAddsQuantities();
        unknownCodeIsRejected();
        if (failures > 0) {
            System.out.println(failures + " test(s) failed");
            System.exit(1);
        }
        System.out.println("All tests passed");
    }

    static void totalWithoutDiscount() {
        Cart cart = new Cart();
        cart.addItem("pen", 150, 2);
        cart.addItem("book", 1200, 1);
        check(cart.totalCents() == 1500, "total without discount should be 1500, was " + cart.totalCents());
    }

    static void itemCountAddsQuantities() {
        Cart cart = new Cart();
        cart.addItem("pen", 150, 2);
        cart.addItem("book", 1200, 3);
        check(cart.itemCount() == 5, "item count should be 5, was " + cart.itemCount());
    }

    static void unknownCodeIsRejected() {
        Cart cart = new Cart();
        try {
            cart.applyDiscountCode("FREE100");
            check(false, "unknown code should be rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "");
        }
    }

    static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + message);
        }
    }
}
