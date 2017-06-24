package tools.timeTravelling;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class DatabaseInfo {
  private DatabaseState state;
  private Object databaseRef;
  private Object rootRef;
  private int version;
  private Lock lock;

  public enum DatabaseState {
    not_stored,
    valid,
    outdated;
  }

  public DatabaseInfo() {
    state = DatabaseState.not_stored;
    version = 0;
    lock = new ReentrantLock();
  }

  public Object getRef() {
    return databaseRef;
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

  public Object getRoot() {
    return rootRef;
  }

  public void setRoot(final Object rootRef) {
     this.rootRef = rootRef;
     update(rootRef);
  }

  public int getVersion() {
    return version;
  }

  public boolean hasVersion(final int version) {
    return ((state == DatabaseState.valid) && (this.version == version));
  }

  public void setVersion(final int version) {
   this.version = version;
   this.state = DatabaseState.valid;
  }

  public void getLock() {
    lock.lock();
  }

  public void releaseLock() {
    lock.unlock();
  }
}
