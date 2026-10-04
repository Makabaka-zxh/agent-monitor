package android.content;

import java.util.HashSet;
import java.util.Set;

public final class IntentFilter {
    private final Set<String> actions = new HashSet<>();
    public IntentFilter() {}
    public IntentFilter(String action) { addAction(action); }
    public void addAction(String action) { actions.add(action); }
    public boolean hasAction(String action) { return actions.contains(action); }
}
