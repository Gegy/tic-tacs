package net.gegy1000.tictacs.chunk.future;

public final class Result<T> {
    private static final Object ERROR_VALUE = new Object();
    private static final Result<?> ERROR = new Result<>(ERROR_VALUE);

    private final Object value;

    private Result(Object value) {
        this.value = value;
    }

    public static <T> Result<T> ok(T result) {
        return new Result<>(result);
    }

    @SuppressWarnings("unchecked")
    public static <T> Result<T> error() {
        return (Result<T>) ERROR;
    }

    @SuppressWarnings("unchecked")
    public T get() {
        if (this.value == ERROR_VALUE) {
            throw new RuntimeException("result is error");
        }
        return (T) this.value;
    }

    public boolean isOk() {
        return this.value != ERROR_VALUE;
    }

    public boolean isError() {
        return this.value == ERROR_VALUE;
    }
}
