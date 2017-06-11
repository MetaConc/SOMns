package tools.timeTravelling;

public class DatabaseInfo {
  private DatabaseState state;
  private Object databaseRef;
  private int version;

  public enum DatabaseState {
    not_stored,
    valid,
    outdated;
  }

  public DatabaseInfo() {
    state = DatabaseState.not_stored;
    databaseRef = null;
    version = 0;
  }

  public Object getRef() {
    return databaseRef;
  }

  public int getVersion() {
    return version;
  }

  public DatabaseState getState() {
    return state;
  }

  public void performedWrite() {
    if (state == DatabaseState.valid) {
      state = DatabaseState.outdated;
    }
  }

  public void update(final Object newRef) {
    version++;
    databaseRef = newRef;
    state = DatabaseState.valid;
  }
}
