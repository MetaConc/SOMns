package som.vmobjects;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import som.vm.constants.Classes;
import som.vmobjects.SArray.SMutableArray;


public class SFileDescriptor extends SObjectWithClass {
  @CompilationFinal public static SClass fileDescriptorClass;

  public static final int BUFFER_SIZE = 32 * 1024;

  private boolean open       = false;
  private SArray  buffer;
  private SSymbol mode;
  private int     bufferSize = BUFFER_SIZE;

  private RandomAccessFile raf;
  private final File       f;

  public static void setSOMClass(final SClass cls) {
    // assert fileDescriptorClass == null || cls == null;
    fileDescriptorClass = cls;
  }

  public SFileDescriptor(final String uri) {
    super(fileDescriptorClass, fileDescriptorClass.getInstanceFactory());
    f = new File(uri);
  }

  public void openFile(final SBlock fail) {
    long[] storage = new long[bufferSize];
    buffer = new SMutableArray(storage, Classes.arrayClass);

    try {
      if (mode.getString().equals("read")) {
        raf = new RandomAccessFile(f, "r");
      } else {
        raf = new RandomAccessFile(f, "rw");
      }
    } catch (FileNotFoundException e) {
      fail.getMethod().invoke(new Object[] {fail, e.toString()});
    }
    /*
     * open with write only
     * raf.getChannel().truncate(0);
     */

    open = true;
  }

  public void closeFile() {
    try {
      raf.close();
      open = false;
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * This method causes the buffer to be filled with bytes read from the file,
   * reading starts from the specified position and the buffer is filled from its start.
   *
   * @param position position in the file where reading starts
   * @param ifFail Handler for failures
   * @return
   */
  public int read(final long position, final SBlock fail) {
    if (!open) {
      fail.getMethod().invoke(new Object[] {fail, "File not open"});
      return 0;
    }

    long[] storage = (long[]) buffer.getStoragePlain();
    byte[] buff = new byte[bufferSize];
    int bytes = 0;

    try {
      assert open;
      assert !mode.getString().equals("write");

      // set position in file
      raf.seek(position);
      bytes = raf.read(buff);
    } catch (IOException e) {
      fail.getMethod().invoke(new Object[] {fail, "File not open"});
    }

    // move read data to the storage
    for (int i = 0; i < bufferSize; i++) {
      storage[i] = buff[i];
    }

    return bytes;
  }

  public void write(final int nBytes, final long position, final SBlock fail) {
    if (!open) {
      fail.getMethod().invoke(new Object[] {fail, "File not open"});
      return;
    }

    long[] storage = (long[]) buffer.getStoragePlain();
    byte[] buff = new byte[bufferSize];

    for (int i = 0; i < bufferSize; i++) {
      // punish users for using this loophole
      assert Byte.MIN_VALUE <= storage[i] && storage[i] <= Byte.MAX_VALUE;
      buff[i] = (byte) storage[i];
    }

    try {
      raf.seek(position);
      raf.write(buff, 0, nBytes);
    } catch (IOException e) {
      fail.getMethod().invoke(new Object[] {fail, "File not open"});
    }
  }

  public long getFileSize() {
    try {
      return raf.length();
    } catch (IOException e) {
      e.printStackTrace();
    }
    return 0;
  }

  public boolean isClosed() {
    return !open;
  }

  @Override
  public boolean isValue() {
    return false;
  }

  public SArray getBuffer() {
    return buffer;
  }

  public int getBufferSize() {
    return bufferSize;
  }

  public void setBufferSize(final int bufferSize) {
    // buffer size only changeable for closed files.
    if (!open) {
      this.bufferSize = bufferSize;
    }
  }

  public void setMode(final SSymbol mode) {
    this.mode = mode;
  }
}
