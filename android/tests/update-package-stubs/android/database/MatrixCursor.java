package android.database;

import java.util.ArrayList;
import java.util.List;

public final class MatrixCursor implements Cursor {
    public final String[] columns;
    public final List<Object> row = new ArrayList<>();
    public MatrixCursor(String[] columns, int capacity) { this.columns = columns.clone(); }
    public RowBuilder newRow() { return new RowBuilder(); }

    public final class RowBuilder {
        public RowBuilder add(Object value) { row.add(value); return this; }
    }
}
