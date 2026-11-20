package demo;

public interface Marker {
    default String marker() {
        return "MARKER";
    }
}
