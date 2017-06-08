package tools.timeTravelling;

public class DatabaseInfo {
  private databaseState state;
  private Long databaseRef;
  private int version;

  public enum databaseState {
    not_stored,
    valid,
    outdated;
  }

  public DatabaseInfo () {
    state = databaseState.not_stored;
    databaseRef = null;
    version = 0;
  }

  public long getRef() {
    return databaseRef;
  }

  public int getVersion() {
    return version;
  }

  public databaseState getState () {
    return state;
  }

  public void performedWrite() {
    if(state == databaseState.valid) {
      state = databaseState.outdated;
    }
  }

  public void update(final long newRef) {
    version++;
    databaseRef = newRef;
    state = databaseState.valid;
  }
}
