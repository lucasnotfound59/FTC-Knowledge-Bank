package example;

public final class Vision {
    public void accept(Result result) {
        consume(result);
    }

    private void consume(Result result) {
        // Fixture only. The acceptance test never compiles or runs robot code.
    }

    private interface Result {
        boolean isValid();
    }
}
