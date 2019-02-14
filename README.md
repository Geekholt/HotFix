# Android热修复

# 一、简述

bug一般是一个或多个class出现了问题，在一个理想的状态下，我们只需将修复好的这些个class更新到用户手机上的app中就可以修复这些bug了。要怎么才能动态更新这些class呢？其实，不管是哪种热修复方案，肯定是如下几个步骤：

1. 下发补丁（内含修复好的class）到用户手机，即让app从服务器上下载（网络传输）
2. app通过**"某种方式"**，使补丁（apk、dex、jar等文件）中的class被app调用（本地更新）

这里的**"某种方式"**，对本篇而言，就是使用Android的类加载器，通过类加载器加载这些修复好的class，覆盖对应有问题的class，理论上就能修复bug了。

# 二、类加载机制

## 1、双亲委派模型

在加载一个字节码文件时，会询问当前的classLoader是否已经加载过此字节码文件。如果加载过，则直接返回，不再重复加载。如果没有加载过，则会询问它的Parent是否已经加载过此字节码文件，同样的，如果已经加载过，就直接返回parent加载过的字节码文件，而如果整个继承线路上的classLoader都没有加载过，才由child类加载器（即，当前的子classLoader）执行类的加载工作。

### 1）特点：

如果一个类被classLoader继承线路上的任意一个加载过，那么在以后整个系统的生命周期中，这个类都不会再被加载，大大提高了类的加载效率。

### 2）作用：

1. 类加载的共享功能

> 一些Framework层级的类一旦被顶层classLoader加载过，会缓存到内存中，以后在任何地方用到，都不会去重新加载。

1. 类加载的隔离功能

> 共同继承线程上的classLoader加载的类，肯定不是同一个类，这样可以避免某些开发者自己去写一些代码冒充核心类库，来访问核心类库中可见的成员变量。如java.lang.String在应用程序启动前就已经被系统加载好了，如果在一个应用中能够简单的用自定义的String类把系统中的String类替换掉的话，会有严重的安全问题。

验证多个类是同一个类的成立条件：

- 相同的className
- 相同的packageName
- 被相同的classLoader加载

### 3）loadClass()

通过loadClass()这个方法来验证双亲委派模型

找到ClassLoader这个类中的loadClass()方法，它调用的是另一个2个参数的重载loadClass()方法。

```java
public Class<?> loadClass(String name) throws ClassNotFoundException {
    return loadClass(name, false);
}
```

找到最终这个真正的loadClass()方法，下面便是该方法的源码：

```java
protected Class<?> loadClass(String name, boolean resolve)
    throws ClassNotFoundException
{
    // First, check if the class has already been loaded
    Class<?> c = findLoadedClass(name);
    if (c == null) {
        try {
            if (parent != null) {
                c = parent.loadClass(name, false);
            } else {
                c = findBootstrapClassOrNull(name);
            }
        } catch (ClassNotFoundException e) {
            // ClassNotFoundException thrown if class not found
            // from the non-null parent class loader
        }

        if (c == null) {
            // If still not found, then invoke findClass in order
            // to find the class.
            c = findClass(name);
        }
    }
    return c;
}
```

可以看到，如前面所说，加载一个类时，会有如下3步：

1. 检查当前的classLoader是否已经加载琮这个class，有则直接返回，没有则进行第2步。
2. 调用父classLoader的loadClass()方法，检查父classLoader是否有加载过这个class，有则直接返回，没有就继续检查上上个父classLoader，直到顶层classLoader。
3. 如果所有的父classLoader都没有加载过这个class，则最终由当前classLoader调用findClass()方法，去dex文件中找出并加载这个class。

# 二、Android中的ClassLoader

## 1、类加载器类型

Android跟java有很大的渊源，基于jvm的java应用是通过ClassLoader来加载应用中的class的，Android对jvm优化过，使用的是dalvik虚拟机，且**class文件会被打包进一个dex文件中**，底层虚拟机有所不同，那么它们的类加载器当然也是会有所区别。

Android中最主要的类加载器有如下4个：

- BootClassLoader：加载Android Framework层中的class字节码文件（类似java的Bootstrap ClassLoader）
- PathClassLoader：加载已经安装到系统中的Apk的class字节码文件（类似java的App ClassLoader）
- DexClassLoader：加载制定目录的class字节码文件（类似java中的Custom ClassLoader）
- BaseDexClassLoader：PathClassLoader和DexClassLoader的父类

一个app一定会用到**BootClassLoader**、**PathClassLoader**这2个类加载器，可通过如下代码进行验证：

```java
@Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ClassLoader classLoader = getClassLoader();
        if (classLoader != null) {
            Log.e(TAG, "classLoader = " + classLoader);
            while (classLoader.getParent() != null) {
                classLoader = classLoader.getParent();
                Log.e(TAG, "classLoader = " + classLoader);
            }
        }
    }
```

上面代码中可以通过上下文拿到当前类的类加载器（PathClassLoader）,然后通过getParent()得到父类加载器（BootClassLoader），这是由于Android中的类加载器和java类加载器一样使用的是双亲委派模型。

## 2、PathClassLoader与DexClassLoader的区别

一般的源码在Android Studio中可以查到，但 **PathClassLoader** 和 **DexClassLoader** 的源码是属于系统级源码，所以无法在Android Studio中直接查看。可以到[androidxref.com](https://link.juejin.im/?target=http%3A%2F%2Fandroidxref.com)这个网站上直接查看，下面会列出之后要分析的几个类的源码地址。

以下是Android 5.0中的部分源码：

- [PathClassLoader.java](https://link.juejin.im/?target=http%3A%2F%2Fandroidxref.com%2F5.0.0_r2%2Fxref%2Flibcore%2Fdalvik%2Fsrc%2Fmain%2Fjava%2Fdalvik%2Fsystem%2FPathClassLoader.java)
- [DexClassLoader.java](https://link.juejin.im/?target=http%3A%2F%2Fandroidxref.com%2F5.0.0_r2%2Fxref%2Flibcore%2Fdalvik%2Fsrc%2Fmain%2Fjava%2Fdalvik%2Fsystem%2FDexClassLoader.java)
- [BaseDexClassLoader.java](https://link.juejin.im/?target=http%3A%2F%2Fandroidxref.com%2F5.0.0_r2%2Fxref%2Flibcore%2Fdalvik%2Fsrc%2Fmain%2Fjava%2Fdalvik%2Fsystem%2FBaseDexClassLoader.java)
- [DexPathList.java](https://link.juejin.im/?target=http%3A%2F%2Fandroidxref.com%2F5.0.0_r2%2Fxref%2Flibcore%2Fdalvik%2Fsrc%2Fmain%2Fjava%2Fdalvik%2Fsystem%2FDexPathList.java)

### 1）使用场景

先来介绍一下这两种Classloader在使用场景上的区别

- PathClassLoader：只能加载已经安装到Android系统中的apk文件（/data/app目录），是Android默认使用的类加载器。
- DexClassLoader：可以加载任意目录下的dex/jar/apk/zip文件，比PathClassLoader更灵活，是实现热修复的重点。

### 2）代码差异

下面来看一下PathClassLoader与DexClassLoader的源码的差别，都非常简单

```java
// PathClassLoader
public class PathClassLoader extends BaseDexClassLoader {
    public PathClassLoader(String dexPath, ClassLoader parent) {
        super(dexPath, null, null, parent);
    }

    public PathClassLoader(String dexPath, String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }
}
```

```java
// DexClassLoader
public class DexClassLoader extends BaseDexClassLoader {
    public DexClassLoader(String dexPath, String optimizedDirectory,
            String librarySearchPath, ClassLoader parent) {
        super(dexPath, new File(optimizedDirectory), librarySearchPath, parent);
    }
}
```

通过比对，可以得出2个结论：

- PathClassLoader与DexClassLoader都继承于BaseDexClassLoader。
- PathClassLoader与DexClassLoader在构造函数中都调用了父类的构造函数，但DexClassLoader多传了一个optimizedDirectory。

## 3、BaseDexClassLoader

通过观察PathClassLoader与DexClassLoader的源码我们就可以确定，真正有意义的处理逻辑肯定在BaseDexClassLoader中，所以下面着重分析BaseDexClassLoader源码。

### 1）构造函数

先来看看BaseDexClassLoader的构造函数都做了什么：

```java
public class BaseDexClassLoader extends ClassLoader {
    private final DexPathList pathList;
    ...
    public BaseDexClassLoader(String dexPath, File optimizedDirectory, String libraryPath, ClassLoader parent){
        super(parent);
        this.pathList = new DexPathList(this, dexPath, libraryPath, optimizedDirectory);
    }
    ...
}
```

- dexPath：要加载的**程序文件**（一般是dex文件，也可以是jar/apk/zip文件）所在目录。
- optimizedDirectory：dex文件的输出目录（因为在加载jar/apk/zip等压缩格式的程序文件时会解压出其中的dex文件，该目录就是专门用于存放这些被解压出来的dex文件的）。
- libraryPath：加载程序文件时需要用到的库路径。
- parent：父加载器

***tip：**从一个完整App的角度来说，**程序文件**指定的就是apk包中的classes.dex文件；但从热修复的角度来看，程序文件指的是补丁。

> 因为PathClassLoader只会加载已安装包中的dex文件，而DexClassLoader不仅仅可以加载dex文件，还可以加载jar、apk、zip文件中的dex。jar、apk、zip其实就是一些压缩格式，要拿到压缩包里面的dex文件就需要解压，所以，DexClassLoader在调用父类构造函数时会指定一个解压的目录。

### 2）findClass()

类加载器肯定会提供有一个方法来供外界找到它所加载到的class，该方法就是findClass()，不过在PathClassLoader和DexClassLoader源码中都没有重写父类的findClass()方法，但它们的父类BaseDexClassLoader就有重写findClass()，所以来看看BaseDexClassLoader的findClass()方法都做了哪些操作，代码如下：

```java
private final DexPathList pathList;

@Override
protected Class<?> findClass(String name) throws ClassNotFoundException {
    List<Throwable> suppressedExceptions = new ArrayList<Throwable>();
    // 实质是通过pathList的对象findClass()方法来获取class
    Class c = pathList.findClass(name, suppressedExceptions);
    if (c == null) {
        ClassNotFoundException cnfe = new ClassNotFoundException("Didn't find class \"" + name + "\" on path: " + pathList);
        for (Throwable t : suppressedExceptions) {
            cnfe.addSuppressed(t);
        }
        throw cnfe;
    }
    return c;
}
```

可以看到，BaseDexClassLoader的findClass()方法实际上是通过DexPathList对象（pathList）的findClass()方法来获取class的，而这个DexPathList对象恰好在之前的BaseDexClassLoader构造函数中就已经被创建好了。所以，下面就来看看DexPathList类中都做了什么。

## 4、DexPathList

### 1）构造函数

```java
private final Element[] dexElements;

public DexPathList(ClassLoader definingContext, String dexPath,
        String libraryPath, File optimizedDirectory) {
    ...
    this.definingContext = definingContext;
    this.dexElements = makeDexElements(splitDexPath(dexPath), optimizedDirectory,suppressedExceptions);
    ...
}
```

这个构造函数中，保存了当前的类加载器definingContext，并调用了makeDexElements()得到Element集合。

> 通过对splitDexPath(dexPath)的源码追溯，发现该方法的作用其实就是将dexPath目录下的所有程序文件转变成一个File集合。而且还发现，dexPath是一个用冒号（":"）作为分隔符把多个程序文件目录拼接起来的字符串(如：/data/dexdir1:/data/dexdir2:...)。

那接下来无疑是分析makeDexElements()方法了，因为这部分代码比较长，我就贴出关键代码，并以注释的方式进行分析：

```java
private static Element[] makeDexElements(ArrayList<File> files, File optimizedDirectory, ArrayList<IOException> suppressedExceptions) {
    // 1.创建Element集合
    ArrayList<Element> elements = new ArrayList<Element>();
    // 2.遍历所有dex文件（也可能是jar、apk或zip文件）
    for (File file : files) {
        ZipFile zip = null;
        DexFile dex = null;
        String name = file.getName();
        ...
        // 如果是dex文件
        if (name.endsWith(DEX_SUFFIX)) {
            dex = loadDexFile(file, optimizedDirectory);

        // 如果是apk、jar、zip文件（这部分在不同的Android版本中，处理方式有细微差别）
        } else {
            zip = file;
            dex = loadDexFile(file, optimizedDirectory);
        }
        ...
        // 3.将dex文件或压缩文件包装成Element对象，并添加到Element集合中
        if ((zip != null) || (dex != null)) {
            elements.add(new Element(file, false, zip, dex));
        }
    }
    // 4.将Element集合转成Element数组返回
    return elements.toArray(new Element[elements.size()]);
}复制代码
```

在这个方法中，看到了一些眉目，总体来说，DexPathList的构造函数是将一个个的程序文件（可能是dex、apk、jar、zip）封装成一个个Element对象，最后添加到Element集合中。

> 其实，Android的类加载器（不管是PathClassLoader，还是DexClassLoader），它们最后只认dex文件，而loadDexFile()是加载dex文件的核心方法，可以从jar、apk、zip中提取出dex，但这里先不分析了，因为第1个目标已经完成，等到后面再来分析吧。

### 2）findClass()

再来看DexPathList的findClass()方法：

```
public Class findClass(String name, List<Throwable> suppressed) {
    for (Element element : dexElements) {
        // 遍历出一个dex文件
        DexFile dex = element.dexFile;

        if (dex != null) {
            // 在dex文件中查找类名与name相同的类
            Class clazz = dex.loadClassBinaryName(name, definingContext, suppressed);
            if (clazz != null) {
                return clazz;
            }
        }
    }
    if (dexElementsSuppressedExceptions != null) {
        suppressed.addAll(Arrays.asList(dexElementsSuppressedExceptions));
    }
    return null;
}复制代码
```

结合DexPathList的构造函数，其实DexPathList的findClass()方法很简单，就只是对Element数组进行遍历，一旦找到类名与name相同的类时，就直接返回这个class，找不到则返回null。

> 为什么是调用DexFile的loadClassBinaryName()方法来加载class？这是因为一个Element对象对应一个dex文件，而一个dex文件则包含多个class。也就是说Element数组中存放的是一个个的dex文件，而不是class文件。这可以从Element这个类的源码和dex文件的内部结构看出。

# 三、热修复的实现原理

经过对PathClassLoader、DexClassLoader、BaseDexClassLoader、DexPathList的分析，我们知道，安卓的类加载器在加载一个类时会先从自身DexPathList对象中的Element数组中获取（Element[] dexElements）到对应的类，之后再加载。采用的是数组遍历的方式，不过注意，遍历出来的是一个个的dex文件。在for循环中，首先遍历出来的是dex文件，然后再是从dex文件中获取class，所以，我们只要让修复好的class打包成一个dex文件，放于Element数组的第一个元素，这样就能保证获取到的class是最新修复好的class了（当然，有bug的class也是存在的，不过是放在了Element数组的最后一个元素中，所以没有机会被拿到而已。
