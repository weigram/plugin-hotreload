package demo;

public class Target extends BaseA {
    public String extraField = "FIELD";

    public String extra() {
        return "EXTRA";
    }

    public String value() {
        return base();
    }
}
