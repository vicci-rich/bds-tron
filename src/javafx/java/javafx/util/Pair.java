package javafx.util;

/** Minimal shim of javafx.util.Pair for JDK8 builds without bundled JavaFX. */
public class Pair<K, V> {

    private final K key;
    private final V value;

    public Pair(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public K getKey() {
        return key;
    }

    public V getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Pair)) return false;
        Pair<?, ?> other = (Pair<?, ?>) o;
        return (key == null ? other.key == null : key.equals(other.key))
            && (value == null ? other.value == null : value.equals(other.value));
    }

    @Override
    public int hashCode() {
        int h = key != null ? key.hashCode() : 0;
        h = 31 * h + (value != null ? value.hashCode() : 0);
        return h;
    }

    @Override
    public String toString() {
        return "[" + String.valueOf(key) + ", " + String.valueOf(value) + "]";
    }
}
