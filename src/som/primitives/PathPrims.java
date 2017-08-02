package som.primitives;

import java.io.File;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;

import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.Specialization;

import bd.primitives.Primitive;
import som.interpreter.nodes.nary.BinaryExpressionNode;
import som.interpreter.nodes.nary.TernaryExpressionNode;
import som.interpreter.nodes.nary.UnaryExpressionNode;
import som.vm.constants.Classes;
import som.vm.constants.Nil;
import som.vmobjects.SArray.SImmutableArray;
import som.vmobjects.SBlock;


public final class PathPrims {
  @GenerateNodeFactory
  @Primitive(primitive = "pathContents:")
  public abstract static class PathContentsPrim extends UnaryExpressionNode {
    @Specialization
    public final Object getContents(final String directory) {
      File f = new File(directory);
      String[] content = f.list();
      Object[] o = new Object[content.length];
      for (int i = 0; i < content.length; i++) {
        o[i] = content[i];
      }

      if (content != null) {
        SImmutableArray sia = new SImmutableArray(o, Classes.arrayClass);
        return sia;
      } else {
        return Nil.nilObject;
      }
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "path:copyAs:ifFail:")
  public abstract static class FileCopyPrim extends TernaryExpressionNode {
    @Specialization
    public final Object copyAs(final String source, final String dest, final SBlock fail) {
      try {
        Files.copy(Paths.get(source), Paths.get(dest), new CopyOption[0]);
      } catch (IOException e) {
        fail.getMethod().invoke(new Object[] {fail, e.toString()});
      }
      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathCreateDirectory:ifFail:")
  public abstract static class CreateDireytoryPrim extends BinaryExpressionNode {
    @Specialization
    public final Object createDirectory(final String dir, final SBlock fail) {
      try {
        Files.createDirectories(Paths.get(dir), new FileAttribute<?>[0]);
      } catch (IOException e) {
        fail.getMethod().invoke(new Object[] {fail, e.toString()});
      }
      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathDeleteFileDir:ifFail:")
  public abstract static class DeleteDireytoryPrim extends BinaryExpressionNode {
    @Specialization
    public final Object delteDirectory(final String dir, final SBlock fail) {
      try {
        Files.delete(Paths.get(dir));
      } catch (IOException e) {
        fail.getMethod().invoke(new Object[] {fail, e.toString()});
      }
      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathFileExists:")
  public abstract static class PathExistsPrim extends UnaryExpressionNode {
    @Specialization
    public final boolean exists(final String dir) {
      return Files.exists(Paths.get(dir), new LinkOption[0]);
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathIsDirectory:")
  public abstract static class IsDirectoryPrim extends UnaryExpressionNode {
    @Specialization
    public final boolean isDirectory(final String dir) {
      return Files.isDirectory(Paths.get(dir), new LinkOption[0]);
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathIsReadOnly:")
  public abstract static class IsReadOnlyPrim extends UnaryExpressionNode {
    @Specialization
    public final boolean isReadOnly(final String dir) {
      return Files.isReadable(Paths.get(dir)) && !Files.isWritable(Paths.get(dir));
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathLastModified:")
  public abstract static class LastModifiedPrim extends UnaryExpressionNode {
    @Specialization
    public final String lastModified(final String dir) {
      try {
        return Files.getLastModifiedTime(Paths.get(dir), new LinkOption[0]).toString();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "path:moveAs:ifFail:")
  public abstract static class FileMovePrim extends TernaryExpressionNode {
    @Specialization
    public final Object moveAs(final String source, final String dest, final SBlock fail) {
      try {
        Files.move(Paths.get(source), Paths.get(dest), new CopyOption[0]);
      } catch (IOException e) {
        fail.getMethod().invoke(new Object[] {fail, e.toString()});
      }
      return Nil.nilObject;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathGetSize:")
  public abstract static class SizePrim extends UnaryExpressionNode {
    @Specialization
    public final long getSize(final String dir) {
      try {
        return Files.size(Paths.get(dir));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathCurrentDirectory:")
  public abstract static class CurrentDirectoryPrim extends UnaryExpressionNode {
    @Specialization
    public final String getSize(final Object o) {
      return System.getProperty("user.dir");
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathSeparator:")
  public abstract static class SeparatorPrim extends UnaryExpressionNode {
    @Specialization
    public final String getSeparator(final Object o) {
      return File.separator;
    }
  }

  @GenerateNodeFactory
  @Primitive(primitive = "pathIsAbsolute:")
  public abstract static class AbsolutePrim extends UnaryExpressionNode {
    @Specialization
    public final boolean isAbsolute(final String path) {
      Path p = Paths.get(path);
      return p.isAbsolute();
    }
  }
}
