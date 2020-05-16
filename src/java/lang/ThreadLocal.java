package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
public class ThreadLocal<T> {
    /**
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    //看nextHashCode
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     */
    private static AtomicInteger nextHashCode =
            new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     */
    private static int nextHashCode() {
        //原子操作得到HASH_INCREMENT 这个魔数
        //通过这个值会完美的散列在ENTRY的数组里面
        //斐波那契完整的是i * HASH_INCREMENT + HASH_INCREMENT;
        //这里通过getAndAdd，每次进来都会增加，所以跟上面其实是一样的。
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * @return the initial value for this thread-local
     */

    /**
     * 返回当前线程对应的ThreadLocal的初始值
     * 此方法的第一次调用发生在，当线程通过{@link #get}方法访问此线程的ThreadLocal值时
     * 除非线程先调用了 {@link #set}方法，在这种情况下，
     * {@code initialValue} 才不会被这个线程调用。
     * 通常情况下，每个线程最多调用一次这个方法，
     * 但也可能再次调用，发生在调用{@link #remove}方法后，
     * 紧接着调用{@link #get}方法。
     *
     * <p>这个方法仅仅简单的返回null {@code null};
     * 如果程序员想ThreadLocal线程局部变量有一个除null以外的初始值，
     * 必须通过子类继承{@code ThreadLocal} 的方式去重写此方法
     * 通常, 可以通过匿名内部类的方式实现
     *
     * @return 当前ThreadLocal的初始值
     */

    protected T initialValue() {
        //返回null
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     *
     * @return the current thread's value of this thread-local
     */


    /**
     * 返回当前线程中保存ThreadLocal的值
     * 如果当前线程没有此ThreadLocal变量，
     * 则它会通过调用{@link #initialValue} 方法进行初始化值
     *
     * @return 返回当前线程对应此ThreadLocal的值
     */
    public T get() {
        Thread t = Thread.currentThread();
        //进入getMap
        ThreadLocalMap map = getMap(t);
        //一个线程顶一个两个ThreadLocal走这里
        if (map != null) {
            // 进入getEntry()
            // 以当前的ThreadLocal 为 key，调用getEntry获取对应的存储实体e
            ThreadLocalMap.Entry e = map.getEntry(this);
            // 找到对应的存储实体 e
            if (e != null) {
                @SuppressWarnings("unchecked")
                // 获取存储实体 e 对应的 value值
                // 即为我们想要的当前线程对应此ThreadLocal的值
                T result = (T)e.value;
                return result;
            }
        }
        //第一次先走这里 看着里，在demo中被重写的
        // 如果map不存在，则证明此线程没有维护的ThreadLocalMap对象
        // 调用setInitialValue进行初始化
        return setInitialValue();
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * @return the initial value
     */
    /**
     * set的变样实现，用于初始化值initialValue，
     * 用于代替防止用户重写set()方法
     *
     * @return the initial value 初始化后的值
     */
    private T setInitialValue() {
        //进入看一下
        // 调用initialValue获取初始化的值
        T value = initialValue();
        // 获取当前线程对象
        //可能被其他Threadlocal创建了
        Thread t = Thread.currentThread();
        // 获取此线程对象中维护的ThreadLocalMap对象
        ThreadLocalMap map = getMap(t);
        // 如果此map存在
        if (map != null)
            // 存在则调用map.set设置此实体entry
            map.set(this, value);
        else
            //先走这里
            // 1）当前线程Thread 不存在ThreadLocalMap对象
            // 2）则调用createMap进行ThreadLocalMap对象的初始化
            // 3）并将此实体entry作为第一个值存放至ThreadLocalMap中
            createMap(t, value);
        // 返回设置的值value
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     *
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    /**
     * 设置当前线程对应的ThreadLocal的值
     * 大多数子类都不需要重写此方法，
     * 只需要重写 {@link #initialValue}方法代替设置当前线程对应的ThreadLocal的值
     *
     * @param value 将要保存在当前线程对应的ThreadLocal的值
     *
     */
    public void set(T value) {
        Thread t = Thread.currentThread();
        // 获取此线程对象中维护的ThreadLocalMap对象
        ThreadLocalMap map = getMap(t);
        if (map != null)
            // 存在则调用map.set设置此实体entry
            map.set(this, value);
        else
            // 1）当前线程Thread 不存在ThreadLocalMap对象
            // 2）则调用createMap进行ThreadLocalMap对象的初始化
            // 3）并将此实体entry作为第一个值存放至ThreadLocalMap中
            createMap(t, value);
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
    /**
     * 删除当前线程中保存的ThreadLocal对应的实体entry
     * 如果此ThreadLocal变量在当前线程中调用 {@linkplain #get read}方法
     * 则会通过调用{@link #initialValue}进行再次初始化，
     * 除非此值value是通过当前线程内置调用 {@linkplain #set set}设置的
     * 这可能会导致在当前线程中多次调用{@code initialValue}方法
     *
     * @since 1.5
     */
    public void remove() {
        // 获取当前线程对象中维护的ThreadLocalMap对象
        ThreadLocalMap m = getMap(Thread.currentThread());
        // 如果此map存在
        if (m != null)
            // 存在则调用map.remove
            // 以当前ThreadLocal为key删除对应的实体entry
            m.remove(this);
    }

    /**
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param  t the current thread
     * @return the map
     */
    /**
     * 获取当前线程Thread对应维护的ThreadLocalMap
     *
     * @param  t the current thread 当前线程
     * @return the map 对应维护的ThreadLocalMap
     */
    ThreadLocalMap getMap(Thread t) {
        //返回当前线程的threadLocals
        return t.threadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map
     */
    /**
     * 为当前线程Thread 创建对应维护的ThreadLocalMap.
     *
     * @param t the current thread 当前线程
     * @param firstValue 第一个要存放的ThreadLocal变量值
     */
    void createMap(Thread t, T firstValue) {
        //<this,value>的map 进入构造方法看看
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     */
    /**
     * ThreadLocalMap 是一个定制的自定义 hashMap 哈希表，只适合用于维护
     * 线程对应ThreadLocal的值. 此类的方法没有在ThreadLocal 类外部暴露，
     * 此类是私有的，允许在 Thread 类中以字段的形式声明 ，
     * 以助于处理存储量大，生命周期长的使用用途，
     * 此类定制的哈希表实体键值对使用弱引用WeakReferences 作为key，
     * 但是, 一旦引用不在被使用，
     * 只有当哈希表中的空间被耗尽时，对应不再使用的键值对实体才会确保被 移除回收。
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         */
        //构建Entry

        /**
         * 实体entries在此hash map中是继承弱引用 WeakReference,
         * 使用ThreadLocal 作为 key 键.  请注意，当key为null（i.e. entry.get()
         * == null) 意味着此key不再被引用,此时实体entry 会从哈希表中删除。
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /** The value associated with this ThreadLocal. */
            /** 当前 ThreadLocal 对应储存的值value. */
            Object value;
            //需要Key和value
            Entry(ThreadLocal<?> k, Object v) {
                //super是谁
                super(k);
                value = v;
            }
        }

        /**
         * The initial capacity -- MUST be a power of two.
         */
        /**
         * 初始容量大小 16 -- 必须是2的n次方.
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         */
        /**
         * 底层哈希表 table, 必要时需要进行扩容.
         * 底层哈希表 table.length 长度必须是2的n次方.
         */
        private Entry[] table;

        /**
         * The number of entries in the table.
         */
        /**
         * 实际存储键值对元素个数 entries.
         */
        private int size = 0;

        /**
         * The next size value at which to resize.
         */
        /**
         * 下一次扩容时的阈值
         */
        private int threshold; // Default to 0

        /**
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         */
        /**
         * 设置触发扩容时的阈值 threshold
         * 阈值 threshold = 底层哈希表table的长度 len * 2 / 3
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * Increment i modulo len.
         */
        /**
         * 获取该位置i对应的下一个位置index
         */
        private static int nextIndex(int i, int len) {
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * Decrement i modulo len.
         */
        /**
         * 获取该位置i对应的上一个位置index
         */
        private static int prevIndex(int i, int len) {
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         */
        /**
         * 用于创建一个新的hash map包含 (firstKey, firstValue).
         * ThreadLocalMaps 构造方法是延迟加载的,所以我们只会在至少有一个
         * 实体entry存放时，才初始化创建一次（仅初始化一次）。
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            // 初始化 table 初始容量为 16
            table = new Entry[INITIAL_CAPACITY];
            // 计算当前entry的存储位置
            // 存储位置计算等价于：
            // ThreadLocal 的 hash 值 threadLocalHashCode  % 哈希表的长度 length
            //斐波那契
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            //放到Entry数组7e中
            // 存储当前的实体，key 为 : 当前ThreadLocal  value：真正要存储的值
            table[i] = new Entry(firstKey, firstValue);
            // 设置当前实际存储元素个数 size 为 1
            size = 1;
            // 设置阈值，为初始化容量 16 的 2/3。
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         *
         * @param parentMap the map associated with parent thread.
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            for (int j = 0; j < len; j++) {
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if (key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         *
         * @param  key the thread local object
         * @return the entry associated with key, or null if no such
         */
        /**
         * 根据key 获取对应的实体 entry.  此方法快速适用于获取某一存在key的
         * 实体 entry，否则，应该调用getEntryAfterMiss方法获取，这样做是为
         * 了最大限制地提高直接命中的性能
         *
         * @param  key 当前thread local 对象
         * @return the entry 对应key的 实体entry, 如果不存在，则返回null
         */
        private Entry getEntry(ThreadLocal<?> key) {
            //看threadLocalHashCode
            //斐波那契额threadLocalHashCode
            // 计算要获取的entry的存储位置
            // 存储位置计算等价于：
            // ThreadLocal 的 hash 值 threadLocalHashCode  % 哈希表的长度 length
            int i = key.threadLocalHashCode & (table.length - 1);
            // 获取到对应的实体 Entry
            Entry e = table[i];
            // 存在对应实体并且对应key相等，即同一ThreadLocal
            if (e != null && e.get() == key) // 在key计算hash的位置上直接命中查询，直接返回该entry
                // 返回对应的实体Entry
                return e;
            else
                // 如果在key计算hash的位置上直接命中查询，直接返回该entry，否则调用getEntryAfterMiss并返回结果。
                // 不存在 或 key不一致，则通过调用getEntryAfterMiss继续查找
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         *
         * @param  key the thread local object
         * @param  i the table index for key's hash code
         * @param  e the entry at table[i]
         * @return the entry associated with key, or null if no such
         */
        // 如果在key计算hash的位置上直接命中查询，直接返回该entry，否则调用getEntryAfterMiss并返回结果。

        /**
         * 当根据key找不到对应的实体entry 时，调用此方法。
         * 直接定位到对应的哈希表位置
         *
         * @param  key 当前thread local 对象
         * @param  i 此对象在哈希表 table中的存储位置 index
         * @param  e the entry 实体对象
         * @return the entry 对应key的 实体entry, 如果不存在，则返回null
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            Entry[] tab = table;
            int len = tab.length;
            // 循环遍历当前位置的所有实体entry
            while (e != null) {// 从i位置开始遍历，寻找key能对应上的entry
                // 获取当前entry 的 key ThreadLocal
                ThreadLocal<?> k = e.get();
                // 比较key是否一致，一致则返回
                if (k == key)
                    return e;
                // 找到对应的entry ，但其key 为 null，则证明引用已经不存在
                // 这是因为Entry继承的是WeakReference，这是弱引用带来的坑
                if (k == null)
                    // 删除过期(stale)的entry
                    expungeStaleEntry(i);// 遇到key为null的entry，调用expungeStaleEntry方法，见代码3
                else
                    // key不一致 ，key也不为空，则遍历下一个位置，继续查找
                    i = nextIndex(i, len);
                // 获取下一个位置的实体 entry
                e = tab[i];
            }
            // 遍历完毕，找不到则返回null
            return null;  // 实在没有找到，只能返回null了
        }

        /**
         * Set the value associated with key.
         *
         * @param key the thread local object
         * @param value the value to be set
         */

        /**
         * 设置对应ThreadLocal的值
         *
         * @param key 当前thread local 对象
         * @param value 要设置的值
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            // 我们不会像get()方法那样使用快速设置的方式，
            // 因为通常很少使用set()方法去创建新的实体
            // 相对于替换一个已经存在的实体, 在这种情况下,
            // 快速设置方案会经常失败。

            // 获取对应的底层哈希表 table
            Entry[] tab = table;
            // 获取哈希表长度
            int len = tab.length;
            // 计算对应threalocal的存储位置
            //斐波那契
            int i = key.threadLocalHashCode & (len-1);
            //遍历Entry数组，拿到ThreadLocal属性，已经存在的情况
            // 循环遍历table对应该位置的实体，查找对应的threadLocal
            //找到为key的直接替换，找到为null也直接替换。
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                // 获取当前位置的ThreadLocal
                ThreadLocal<?> k = e.get();

                //如果key相等 替换
                // 如果key threadLocal一致，则证明找到对应的threadLocal
                if (k == key) {
                    // 赋予新值
                    e.value = value;
                    // 结束
                    return;
                }

                //key=null  把当前下标直接移除掉。
                //key什么时候为空？
                //因为Entry的key是弱引用为null的时候会被回收掉，所以这里去掉这里的存储。
                // 如果当前位置的key threadLocal为null
                if (k == null) {
                    // 替换该位置key == null 的实体为当前要设置的实体
                    //i是当前key=null的下标
                    replaceStaleEntry(key, value, i);
                    // 结束
                    return;
                }
            }

            //不存在的话，增加一个下标，增加一个size
            // 当前位置的k ！= key  && k != null
            // 创建新的实体，并存放至当前位置i
            //没有key相等的，也没有为null的
            tab[i] = new Entry(key, value);
            // 实际存储键值对元素个数 + 1
            int sz = ++size;
            //超过阈值，扩容
            // 由于弱引用带来了这个问题，所以先要清除无用数据，才能判断现在的size有没有达到阀值threshhold
            // 如果没有要清除的数据，存储元素个数仍然 大于 阈值 则扩容
            //因为如果清除了的话，这次只加了一个，如果之前没有到达阈值这次一定也不会到达阈值
            //清除成功就不需要扩容了。
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                // 扩容
                rehash();
        }

        /**
         * Remove the entry for key.
         */
        /**
         * 移除对应ThreadLocal的实体
         */
        private void remove(ThreadLocal<?> key) {
            // 获取对应的底层哈希表 table
            Entry[] tab = table;
            // 获取哈希表长度
            int len = tab.length;
            // 计算对应threalocal的存储位置
            int i = key.threadLocalHashCode & (len-1);
            // 循环遍历table对应该位置的实体，查找对应的threadLocal
            //为什么从i开始往后遍历？扩容之后，key相等的变了位置，斐波那契计算的大于等于原来的数字，所以只可能在后面
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                // 如果key threadLocal一致，则证明找到对应的threadLocal
                if (e.get() == key) {
                    // 执行清除操作
                    e.clear();
                    // 清除此位置的实体
                    expungeStaleEntry(i);
                    // 结束
                    return;
                }
            }
        }

        /**
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         *
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         *
         * @param  key the key
         * @param  value the value to be associated with key
         * @param  staleSlot index of the first stale entry encountered while
         *         searching for key.
         */
        /**
         * 当执行set操作时，获取对应的key threadLocal，并替换过期的实体
         * 将这个value值存储在对应key threadLocal的实体中，无论是否已经存在体
         * 对应的key threadLocal
         *
         * 有一个副作用, 此方法会删除该位置下和该位置nextIndex对应的所有过期的实体
         *
         * @param  key 当前thread local 对象
         * @param  value 当前thread local 对象对应存储的值
         * @param  staleSlot 第一次找到此过期的实体对应的位置索引index
         *
         */
        // staleSlot是当前key=null的下标
        //主要做了两件事情，这个方法比较难理解，记住两件事情先这样。  概括替换并擦除
        //尝试查找和传入key对应的Entry，找到则替换，没找到则在传入的staleSlot处插入一个新的Entry；
        // 在上面的过程中，尽力地去擦除一些找到的staleSlot
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            // 获取对应的底层哈希表 table
            Entry[] tab = table;
            // 获取哈希表长度
            int len = tab.length;
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).
            // 往前找，找到table中第一个过期的实体的下标
            // 清理整个table是为了避免因为垃圾回收带来的连续增长哈希的危险
            // 也就是说，哈希表没有清理干净，当GC到来的时候，后果很严重

            // 记录要清除的位置的起始首位置
            // slotToExpunge要清除的位置
            // 传进来的key=null的位置
            int slotToExpunge = staleSlot;
            // 从该位置开始，往前遍历查找第一个过期的实体的下标
            //为什么往前找？因为这段时间可能前面可能又有了
            for (int i = prevIndex(staleSlot, len); (e = tab[i]) != null; i = prevIndex(i, len))
                //key=null
                if (e.get() == null)
                    slotToExpunge = i;

            // Find either the key or trailing null slot of run, whichever
            // occurs first
            // 找到key一致的ThreadLocal或找到一个key为 null的
            for (int i = nextIndex(staleSlot, len); (e = tab[i]) != null; i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.
                // 如果我们找到了key，那么我们就需要把它跟新的过期数据交换来保持哈希表的顺序
                // 那么剩下的过期Entry呢，就可以交给expungeStaleEntry方法来擦除掉
                // 将新设置的实体放置在此过期的实体的位置上

                // 这个时候是从数组下标小的往下标大的方向遍历，i++，刚好跟上面相反。
                //这两个遍历就是为了在左边遇到的第一个空的entry到右边遇到的第一空的 entry之间查询所有过期的对象。
                //注意：在右边如果找到需要设置值的key（这个例子是key=15）相同的时候就开始清理，然后返回，不再继续遍历下去了
                if (k == key) {
                    // 该Entry的key和传入的key相等, 则将传入的value替换掉该Entry的value
                    e.value = value;
                    //与无效的sloat进行交换
                    // 将i位置和staleSlot位置的元素对换（staleSlot位置较前，是要清除的元素）

                    tab[i] = tab[staleSlot];
                    //将这个entry直接放到原来key为null的地方，相当于release了。
                    //总结：如果key相等，就覆盖为这次设置的value，然后替换掉key=null的地方。
                    tab[staleSlot] = e;
                    // Start expunge at preceding stale entry if it exists
                    // 如果相等, 则代表上面的向前寻找key为null的遍历没有找到，
                    // 即staleSlot位置前面的元素没有需要清除的，此时将slotToExpunge设置为i,
                    // 因为原staleSlot的元素已经被放到i位置了，这时位置i前面的元素都不需要清除
                    if (slotToExpunge == staleSlot)
                        slotToExpunge = i;
                    // 在这里开始清除过期数据
                    //从slotToExpunge开始做一次连续的清理
                    // slotToExpunge此时是i
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }
                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                // / 如果我们没有在往后查找中找没有找到过期的实体，
                // 那么slotToExpunge就是第一个过期Entry的下标了
                if (k == null && slotToExpunge == staleSlot)
                    slotToExpunge = i;
            }

            // If key not found, put new entry in stale slot
            // 最后key仍没有找到，则将要设置的新实体放置
            // 在原过期的实体对应的位置上。
            //就是直接放在key=null的原始的地方上面。
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them
            // 如果该位置对应的其他关联位置存在过期实体，则清除
            // 如果slotToExpunge!=staleSlot，代表除了staleSlot位置还有其他位置的元素需要清除
            // 需要清除的定义：key为null的Entry，调用cleanSomeSlots方法清除key为null的Entry
            if (slotToExpunge != staleSlot)
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * Expunge a stale entry by rehashing any possibly colliding entries
         * lying between staleSlot and the next null slot.  This also expunges
         * any other stale entries encountered before the trailing null.  See
         * Knuth, Section 6.4
         *
         * @param staleSlot index of slot known to have null key
         * @return the index of the next null slot after staleSlot
         * (all between staleSlot and this slot will have been checked
         * for expunging).
         */
        /**
         * 删除对应位置的过期实体，并删除此位置后对应相关联位置key = null的实体
         *
         * @param staleSlot 已知的key = null 的对应的位置索引
         * @return 对应过期实体位置索引的下一个key = null的位置
         * (所有的对应位置都会被检查)
         */
        //Stale陈旧的不新鲜的  expunge 清除
        private int expungeStaleEntry(int staleSlot) {
            // 获取对应的底层哈希表 table
            Entry[] tab = table;
            // 获取哈希表长度
            int len = tab.length;

            // expunge entry at staleSlot
            // 擦除这个位置上的脏数据
            tab[staleSlot].value = null;
            tab[staleSlot] = null;
            size--;

            // Rehash until we encounter null
            // 直到我们找到 Entry e = null，才执行rehash操作
            // 就是遍历完该位置的所有关联位置的实体
            Entry e;
            int i;
            // 查找该位置对应所有关联位置的过期实体，进行擦除操作
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                } else {
                    //处理rehash的情况
                    int h = k.threadLocalHashCode & (len - 1);
                    if (h != i) {
                        //tab[i]就是e
                        // key的hash值不等于目前的index，说明该entry是因为有哈希冲突导致向后移动到当前index位置的
                        tab[i] = null;

                        while (tab[h] != null)// 对该entry，重新进行hash并解决冲突
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            return i; // 返回经过整理后的，位于staleSlot位置后的第一个为null的entry的index值
        }

        /**
         * Heuristically scan some cells looking for stale entries.
         * This is invoked when either a new element is added, or
         * another stale one has been expunged. It performs a
         * logarithmic number of scans, as a balance between no
         * scanning (fast but retains garbage) and a number of scans
         * proportional to number of elements, that would find all
         * garbage but would cause some insertions to take O(n) time.
         *
         * @param i a position known NOT to hold a stale entry. The
         * scan starts at the element after i.
         *
         * @param n scan control: {@code log2(n)} cells are scanned,
         * unless a stale entry is found, in which case
         * {@code log2(table.length)-1} additional cells are scanned.
         * When called from insertions, this parameter is the number
         * of elements, but when from replaceStaleEntry, it is the
         * table length. (Note: all this could be changed to be either
         * more or less aggressive by weighting n instead of just
         * using straight log n. But this version is simple, fast, and
         * seems to work well.)
         *
         * @return true if any stale entries have been removed.
         */
        /**
         * 启发式的扫描查找一些过期的实体并清除，
         * 此方法会再添加新实体的时候被调用,
         * 或者过期的元素被清除时也会被调用.
         * 如果实在没有过期数据，那么这个算法的时间复杂度就是O(log n)
         * 如果有过期数据，那么这个算法的时间复杂度就是O(n)
         *
         * @param i 一个确定不是过期的实体的位置，从这个位置i开始扫描
         *
         * @param n 扫描控制: 有{@code log2(n)} 单元会被扫描,
         * 除非找到了过期的实体, 在这种情况下
         * 有{@code log2(table.length)-1} 的格外单元会被扫描.
         * 当调用插入时, 这个参数的值是存储实体的个数，
         * 但如果调用 replaceStaleEntry方法, 这个值是哈希表table的长度
         * (注意: 所有的这些都可能或多或少的影响n的权重
         * 但是这个版本简单，快速，而且似乎执行效率还可以）
         *
         * @return true 返回true，如果有任何过期的实体被删除。
         */
        private boolean cleanSomeSlots(int i, int n) {
            boolean removed = false;
            Entry[] tab = table;
            int len = tab.length;
            do {
                i = nextIndex(i, len);
                Entry e = tab[i];
                if (e != null && e.get() == null) {
                    n = len;
                    removed = true;
                    //主要还是这里
                    i = expungeStaleEntry(i);
                }
            } while ( (n >>>= 1) != 0);
            return removed;
        }

        /**
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         */
        /**
         * 哈希表扩容方法
         * 首先扫描整个哈希表table，删除过期的实体
         * 缩小哈希表table大小 或 扩大哈希表table大小，扩大的容量是加倍.
         */
        private void rehash() {
            // 删除所有过期的实体
            expungeStaleEntries();

            // Use lower threshold for doubling to avoid hysteresis
            // 使用较低的阈值threshold加倍以避免滞后
            // 因为上面删除了过期实体，可能导致本该到达阈值却没有
            // 存储实体个数 大于等于 阈值的3/4则扩容
            if (size >= threshold - threshold / 4)
                resize();
        }

        /**
         * Double the capacity of the table.
         */
        /**
         * 扩容方法，以2倍的大小进行扩容
         * 扩容的思想跟HashMap很相似，都是把容量扩大两倍
         * 不同之处还是因为WeakReference带来的
         */
        private void resize() {
            // 记录旧的哈希表
            Entry[] oldTab = table;
            // 记录旧的哈希表长度
            int oldLen = oldTab.length;
            // 新的哈希表长度为旧的哈希表长度的2倍
            int newLen = oldLen * 2;
            // 创建新的哈希表
            Entry[] newTab = new Entry[newLen];
            int count = 0;
            // 逐一遍历旧的哈希表table的每个实体，重新分配至新的哈希表中
            for (int j = 0; j < oldLen; ++j) {
                // 获取对应位置的实体
                Entry e = oldTab[j];
                // 如果实体不会null
                if (e != null) {
                    // 获取实体对应的ThreadLocal
                    ThreadLocal<?> k = e.get();
                    // 如果该ThreadLocal 为 null
                    if (k == null) {
                        // 则对应的值也要清除
                        // 就算是扩容，也不能忘了为擦除过期数据做准备
                        e.value = null; // Help the GC
                    } else {
                        // 如果不是过期实体，则根据新的长度重新计算存储位置
                        int h = k.threadLocalHashCode & (newLen - 1);
                        // 将该实体存储在对应ThreadLocal的最后一个位置
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }
            // 重新分配位置完毕，则重新计算阈值Threshold
            setThreshold(newLen);
            // 记录实际存储元素个数
            size = count;
            // 将新的哈希表赋值至底层table
            table = newTab;
        }

        /**
         * Expunge all stale entries in the table.
         */
        /**
                * 删除所有过期的实体
         */
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    expungeStaleEntry(j);
            }
        }
    }
}
