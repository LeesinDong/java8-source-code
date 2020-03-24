package java.util.concurrent;

import java.io.ObjectStreamField;
import java.io.Serializable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import java.util.stream.Stream;


public class ConcurrentHashMap<K, V> extends AbstractMap<K, V>
        implements ConcurrentMap<K, V>, Serializable {
    private static final long serialVersionUID = 7249069246763182397L;


    /* ---------------- Constants -------------- */

    /**
     * node数组最大容量
     */
    private static final int MAXIMUM_CAPACITY = 1 << 30;


    /**
     * 默认初始值，必须是2的幂数
     */
    private static final int DEFAULT_CAPACITY = 16;

    /**
     * 数组可能最大值，需要与toArray（）相关方法关联
     */
    static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * 并发级别，遗留下来的，为兼容以前的版本
     */
    private static final int DEFAULT_CONCURRENCY_LEVEL = 16;

    /**
     * 负载因子
     */
    private static final float LOAD_FACTOR = 0.75f;

    /**
     * 链表转树的阀值，如果table[i]下面的链表长度大于8时就转化为数
     */
    static final int TREEIFY_THRESHOLD = 8;

    /**
     * 树转链表的阀值，小于等于6是转为链表，仅在扩容tranfer时才可能树转链表
     */
    static final int UNTREEIFY_THRESHOLD = 6;

    /**
     * 在转变成树之前，还会有一次判断，只有键值对数量大于 64 才会发生转换。
     * 这是为了避免在哈希表建立初期，多个键值对恰好被放入了同一个链表中而导致不必要的转化。
     */
    static final int MIN_TREEIFY_CAPACITY = 64;

    /**
     * Minimum number of rebinnings per transfer step. Ranges are
     * subdivided to allow multiple resizer threads.  This value
     * serves as a lower bound to avoid resizers encountering
     * excessive memory contention.  The value should be at least
     * DEFAULT_CAPACITY.
     */
    private static final int MIN_TRANSFER_STRIDE = 16;

    /**
     * The number of bits used for generation stamp in sizeCtl.
     * Must be at least 6 for 32bit arrays.
     */
    private static int RESIZE_STAMP_BITS = 16;

    /**
     * 2^15-1，help resize的最大线程数
     */
    private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1;

    /**
     * 32-16=16，sizeCtl中记录size大小的偏移量
     */
    private static final int RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS;

    /*
     * Encodings for Node hash fields. See above for explanation.
     */
    static final int MOVED = -1; // hash for forwarding nodes （forwarding nodes的hash值）、标示位
    static final int TREEBIN = -2; // hash值是-2  表示这是一个TreeBin节点
    static final int RESERVED = -3; // hash for transient reservations
    static final int HASH_BITS = 0x7fffffff; // usable bits of normal node hash （ReservationNode的hash值）

    /**
     * 可用处理器数量
     */
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * For serialization compatibility.
     */
    private static final ObjectStreamField[] serialPersistentFields = {
            new ObjectStreamField("segments", Segment[].class),
            new ObjectStreamField("segmentMask", Integer.TYPE),
            new ObjectStreamField("segmentShift", Integer.TYPE)
    };

    /* ---------------- Nodes -------------- */

    /**
     * Node是最核心的内部类，它包装了key-value键值对，所有插入ConcurrentHashMap的数据都包装在这里面。
     * 它与HashMap中的定义很相似，但是但是有一些差别，它对value和next属性设置了volatile同步锁，
     * 它不允许调用setValue方法直接改变Node的value域，它增加了find方法辅助map.get()方法。
     */
    static class Node<K, V> implements Map.Entry<K, V> {
        final int hash;
        final K key;
        //val和next都会在扩容时发生变化，所以加上volatile来保持可见性和禁止重排序
        volatile V val;
        volatile Node<K, V> next;

        Node(int hash, K key, V val, Node<K, V> next) {
            this.hash = hash;
            this.key = key;
            this.val = val;
            this.next = next;
        }

        public final K getKey() {
            return key;
        }

        public final V getValue() {
            return val;
        }

        /**
         * HashMap中Node类的hashCode()方法中的代码为：Objects.hashCode(key) ^ Objects.hashCode(value)
         * 而Objects.hashCode(key)最终也是调用了 key.hashCode()，但是效果一样
         */
        public final int hashCode() {
            return key.hashCode() ^ val.hashCode();
        }

        public final String toString() {
            return key + "=" + val;
        }

        //不允许直接改变value的值
        public final V setValue(V value) {
            throw new UnsupportedOperationException();
        }

        /**
         * HashMap使用if (o == this)，且嵌套if；ConcurrentHashMap使用&&
         */
        public final boolean equals(Object o) {
            Object k, v, u;
            Map.Entry<?, ?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?, ?>) o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == (u = val) || v.equals(u)));
        }

        /**
         * 增加find方法辅助get方法 ，HashMap中的Node类中没有此方法
         */
        Node<K, V> find(int h, Object k) {
            Node<K, V> e = this;
            if (k != null) {
                do {
                    K ek;
                    if (e.hash == h &&
                            ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                } while ((e = e.next) != null);
            }
            return null;
        }
    }

    /* ---------------- Static utilities -------------- */

    /**
     * 对hashCode进行再散列，算法为(h ^ (h >>> 16)) & HASH_BITS
     */
    static final int spread(int h) {
        return (h ^ (h >>> 16)) & HASH_BITS;
    }

    /**
     * 返回大于等于count的最小的2的幂次方
     */
    private static final int tableSizeFor(int c) {
        int n = c - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /**
     * Returns x's Class if it is of the form "class C implements
     * Comparable<C>", else null.
     */
    static Class<?> comparableClassFor(Object x) {
        if (x instanceof Comparable) {
            Class<?> c;
            Type[] ts, as;
            Type t;
            ParameterizedType p;
            if ((c = x.getClass()) == String.class) // bypass checks
                return c;
            if ((ts = c.getGenericInterfaces()) != null) {
                for (int i = 0; i < ts.length; ++i) {
                    if (((t = ts[i]) instanceof ParameterizedType) &&
                            ((p = (ParameterizedType) t).getRawType() ==
                                    Comparable.class) &&
                            (as = p.getActualTypeArguments()) != null &&
                            as.length == 1 && as[0] == c) // type arg is c
                        return c;
                }
            }
        }
        return null;
    }

    /**
     * Returns k.compareTo(x) if x matches kc (k's screened comparable
     * class), else 0.
     */
    @SuppressWarnings({"rawtypes", "unchecked"}) // for cast to Comparable
    static int compareComparables(Class<?> kc, Object k, Object x) {
        return (x == null || x.getClass() != kc ? 0 :
                ((Comparable) k).compareTo(x));
    }

    /* ---------------- Table element access -------------- */

    /*
     * Volatile access methods are used for table elements as well as
     * elements of in-progress next table while resizing.  All uses of
     * the tab arguments must be null checked by callers.  All callers
     * also paranoically precheck that tab's length is not zero (or an
     * equivalent check), thus ensuring that any index argument taking
     * the form of a hash value anded with (length - 1) is a valid
     * index.  Note that, to be correct wrt arbitrary concurrency
     * errors by users, these checks must operate on local variables,
     * which accounts for some odd-looking inline assignments below.
     * Note that calls to setTabAt always occur within locked regions,
     * and so in principle require only release ordering, not
     * full volatile semantics, but are currently coded as volatile
     * writes to be conservative.
     */

    /**
     * 获得在i位置上的Node节点
     */
    @SuppressWarnings("unchecked")
    static final <K, V> Node<K, V> tabAt(Node<K, V>[] tab, int i) {
        return (Node<K, V>) U.getObjectVolatile(tab, ((long) i << ASHIFT) + ABASE);
    }

    /**
     * 利用CAS算法设置i位置上的Node节点（将c和table[i]比较，相同则插入v）。
     */
    static final <K, V> boolean casTabAt(Node<K, V>[] tab, int i,
                                         Node<K, V> c, Node<K, V> v) {
        return U.compareAndSwapObject(tab, ((long) i << ASHIFT) + ABASE, c, v);
    }

    /**
     * 利用volatile方法设置第i个节点的值，这个操作一定是成功的。
     */
    static final <K, V> void setTabAt(Node<K, V>[] tab, int i, Node<K, V> v) {
        U.putObjectVolatile(tab, ((long) i << ASHIFT) + ABASE, v);
    }

    /* ---------------- Fields -------------- */

    /**
     * 存放node的数组,大小是2的幂次方
     */
    transient volatile Node<K, V>[] table;

    /**
     * 扩容时用于存放数据的变量，扩容完成后会置为null。
     */
    private transient volatile Node<K, V>[] nextTable;

    /**
     * 记录容器的容量大小，通过CAS更新
     */
    private transient volatile long baseCount;

    /**
     * 负数代表正在进行初始化或扩容操作 ,其中-1代表正在初始化 ,-N 表示有N-1个线程正在进行扩容操作
     * 正数或0代表hash表还没有被初始化，这个数值表示初始化或下一次进行扩容的大小，类似于扩容阈值。
     * 它的值始终是当前ConcurrentHashMap容量的0.75倍，这与loadfactor是对应的。实际容量>=sizeCtl，则扩容。
     */
    private transient volatile int sizeCtl;//控制标识符

    /**
     * The next table index (plus one) to split while resizing.
     */
    private transient volatile int transferIndex;

    /**
     * 自旋锁 （锁定通过 CAS） 在调整大小和/或创建 CounterCells 时使用。
     * 在CounterCell类更新value中会使用，功能类似显示锁和内置锁，性能更好
     */
    private transient volatile int cellsBusy;//标识当前cell数组是否在初始化或扩容中的CAS标志位

    /**
     * counter cell表，长度总为2的幂次
     */
    private transient volatile CounterCell[] counterCells;// counterCells 数组，总数值的分值分别存在每个 cell 中

    // views
    private transient KeySetView<K, V> keySet;
    private transient ValuesView<K, V> values;
    private transient EntrySetView<K, V> entrySet;


    /* ---------------- Public operations -------------- */

    /**
     * 默认的构造函数
     */
    public ConcurrentHashMap() {
    }

    /**
     * 指定容量的构造函数
     *
     * @param initialCapacity 初始化容量
     * @throws IllegalArgumentException if the initial capacity of
     *                                  elements is negative
     */
    public ConcurrentHashMap(int initialCapacity) {
        if (initialCapacity < 0)
            throw new IllegalArgumentException();
        int cap = ((initialCapacity >= (MAXIMUM_CAPACITY >>> 1)) ?
                MAXIMUM_CAPACITY :
                tableSizeFor(initialCapacity + (initialCapacity >>> 1) + 1));
        this.sizeCtl = cap;//初始化sizeCtl
    }

    /**
     * 创建与给定map具有相同映射的新map
     *
     * @param m the map
     */
    public ConcurrentHashMap(Map<? extends K, ? extends V> m) {
        this.sizeCtl = DEFAULT_CAPACITY;
        putAll(m);
    }

    /**
     * Creates a new, empty map with an initial table size based on
     * the given number of elements ({@code initialCapacity}) and
     * initial table density ({@code loadFactor}).
     *
     * @param initialCapacity 初始容量
     * @param loadFactor      负载因子,当容量达到initialCapacity*loadFactor时，执行扩容
     * @throws IllegalArgumentException if the initial capacity of
     *                                  elements is negative or the load factor is nonpositive
     * @since 1.6
     */
    public ConcurrentHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, 1);
    }

    /**
     * Creates a new, empty map with an initial table size based on
     * the given number of elements ({@code initialCapacity}), table
     * density ({@code loadFactor}), and number of concurrently
     * updating threads ({@code concurrencyLevel}).
     *
     * @param initialCapacity  初始容量
     * @param loadFactor       负载因子,当容量达到initialCapacity*loadFactor时，执行扩容
     * @param concurrencyLevel 预估的并发更新线程数
     * @throws IllegalArgumentException if the initial capacity is
     *                                  negative or the load factor or concurrencyLevel are
     *                                  nonpositive
     */
    public ConcurrentHashMap(int initialCapacity,
                             float loadFactor, int concurrencyLevel) {
        if (!(loadFactor > 0.0f) || initialCapacity < 0 || concurrencyLevel <= 0)
            throw new IllegalArgumentException();
        if (initialCapacity < concurrencyLevel)   // Use at least as many bins
            initialCapacity = concurrencyLevel;   // as estimated threads
        long size = (long) (1.0 + (long) initialCapacity / loadFactor);
        int cap = (size >= (long) MAXIMUM_CAPACITY) ?
                MAXIMUM_CAPACITY : tableSizeFor((int) size);//tableSizeFor容量达到2的幂次方
        this.sizeCtl = cap;
    }

    // Original (since JDK1.2) Map methods

    /**
     * {@inheritDoc}
     */
    public int size() {
        long n = sumCount();
        return ((n < 0L) ? 0 :
                (n > (long) Integer.MAX_VALUE) ? Integer.MAX_VALUE :
                        (int) n);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isEmpty() {
        return sumCount() <= 0L; // ignore transient negative values
    }

    /**
     * 根据key在Map中找出其对应的value，如果不存在key，则返回null，
     * 其中key不允许为null，否则抛异常
     * 对于节点可能在链表或树上的情况，需要分别去查找
     *
     * @throws NullPointerException if the specified key is null
     */
    public V get(Object key) {
        Node<K, V>[] tab;
        Node<K, V> e, p;
        int n, eh;
        K ek;
        int h = spread(key.hashCode());//两次hash计算出hash值
        //根据hash值确定节点位置
        if ((tab = table) != null && (n = tab.length) > 0 &&
                (e = tabAt(tab, (n - 1) & h)) != null) {
            // 搜索到的节点key与传入的key相同且不为null,直接返回这个节点
            if ((eh = e.hash) == h) {
                if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                    return e.val;
            } else if (eh < 0)//如果eh<0 说明这个节点在树上 直接寻找
                return (p = e.find(h, key)) != null ? p.val : null;
            //否则遍历链表 找到对应的值并返回
            while ((e = e.next) != null) {
                if (e.hash == h &&
                        ((ek = e.key) == key || (ek != null && key.equals(ek))))
                    return e.val;
            }
        }
        return null;
    }

    /**
     * 检查table中是否含有key
     *
     * @param key possible key
     * @return {@code true} if and only if the specified object
     * is a key in this table, as determined by the
     * {@code equals} method; {@code false} otherwise
     * @throws NullPointerException if the specified key is null
     */
    public boolean containsKey(Object key) {
        //直接调用get(int key)方法即可，如果有返回值，则说明是包含key的
        return get(key) != null;
    }

    /**
     * 检查在所有映射(k,v)中只要出现一次及以上的v==value，返回true
     * 这个方法可能需要完全遍历Map，因此比containsKey要慢的多
     *
     * @param value value whose presence in this map is to be tested
     * @return {@code true} if this map maps one or more keys to the
     * specified value
     * @throws NullPointerException if the specified value is null
     */
    public boolean containsValue(Object value) {
        if (value == null)
            throw new NullPointerException();
        Node<K, V>[] t;
        if ((t = table) != null) {
            Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
            for (Node<K, V> p; (p = it.advance()) != null; ) {
                V v;
                if ((v = p.val) == value || (v != null && value.equals(v)))
                    return true;
            }
        }
        return false;
    }

    /**
     * 直接调用putVal(key, value, false)方法
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @return the previous value associated with {@code key}, or
     * {@code null} if there was no mapping for {@code key}
     * @throws NullPointerException if the specified key or value is null
     */
    public V put(K key, V value) {
        return putVal(key, value, false);
    }

    /**
     * putVal方法可以分为以下几步：
     * 1、检查key/value是否为空，如果为空，则抛异常，否则进行2
     * 2、进入for死循环，进行3
     * 3、检查table是否初始化了，如果没有，则调用initTable()进行初始化然后进行 2，否则进行4
     * 4、根据key的hash值计算出其应该在table中储存的位置i，取出table[i]的节点用f表示。
     * 根据f的不同有如下三种情况：
     * 1）如果table[i]==null(即该位置的节点为空，没有发生碰撞)，则利用CAS操作直接存储在该位置，如果CAS操作成功则退出死循环。
     * 2）如果table[i]!=null(即该位置已经有其它节点，发生碰撞)，碰撞处理也有两种情况
     * 2.1）检查table[i]的节点的hash是否等于MOVED，如果等于，则检测到正在扩容，则帮助其扩容
     * 2.2）说明table[i]的节点的hash值不等于MOVED，如果table[i]为链表节点，则将此节点插入链表中即可
     * 如果table[i]为树节点，则将此节点插入树中即可。插入成功后，进行 5
     * 5、如果table[i]的节点是链表节点，则检查table的第i个位置的链表是否需要转化为数，如果需要则调用treeifyBin函数进行转化
     */

    //put 方法第一阶段

    final V putVal(K key, V value, boolean onlyIfAbsent) {
        if (key == null || value == null) throw new NullPointerException();// key和value不允许null
        int hash = spread(key.hashCode());//两次hash，减少hash冲突，可以均匀分布 //计算 hash 值
        //用来记录里链表长度
        int binCount = 0;//i处结点标志，0: 未加入新结点, 2: TreeBin或链表结点数, 其它：链表结点数。主要用于每次加入结点后查看是否要由链表转为红黑树
        for (Node<K, V>[] tab = table; ; ) {//这里其实就是自旋操作，当出现线程竞争时不断自旋  //CAS经典写法，不成功无限重试
            Node<K, V> f;
            int n, i, fh;
            //检查是否初始化了，如果没有，则初始化
            if (tab == null || (n = tab.length) == 0)//如果数组为空，则进行数组进行初始化
                tab = initTable(); //初始化数组，这了重点看下
            /**
             * i=(n-1)&hash 等价于i=hash%n(前提是n为2的幂次方).即取出table中位置的节点用f表示。 有如下两种情况：
             * 1、如果table[i]==null(即该位置的节点为空，没有发生碰撞)，则利用CAS操作直接存储在该位置， 如果CAS操作成功则退出死循环。
             * 2、如果table[i]!=null(即该位置已经有其它节点，发生碰撞)
             */
           // 组中的元素，保证每次拿到的数据都是最新的
           // 通过hash值对应的数组下标得到第一个节点；以 volati1e读的方式来读取 table数
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                //如果该下标返回的节点为空，则直接通过cas将新的值封装成node插入即可；
                // 如果cas失败，说明存在竞争，则进入下一次循环
                if (casTabAt(tab, i, null,
                        new Node<K, V>(hash, key, value, null)))
                    break;                   // no lock when adding to empty bin
            }
            //put 方法第三阶段
            else if ((fh = f.hash) == MOVED)//检查table[i]的节点的hash是否等于MOVED，如果等于，则检测到正在扩容，则帮助其扩容
                tab = helpTransfer(tab, f);
            else {//table[i]的节点的hash值不等于MOVED。  //进入到这个分支，说明f是当前 nodes数组对应位置节点的头节点，并且不为空


                //put 方法第四阶段

                //这个方法的主要作用是，如果被添加的节点的位置已经存在节点的时候，需要以链表的方式加入到节点中
                // 如果当前节点已经是一颗红黑树，那么就会按照红黑树的规则将当前节点加入到红黑树中

                V oldVal = null;
                // 针对首个节点进行加锁操作，而不是segment，进一步减少线程冲突
                synchronized (f) {//给对应的头结点加锁
                    if (tabAt(tab, i) == f) { //再次判断对应下标位置是否为f节点
                        if (fh >= 0) {//头结点的hash值大于0，说明是链表
                            binCount = 1;//用来记录链表的长度
                            for (Node<K, V> e = f; ; ++binCount) {//遍历链表
                                K ek;
                                // 如果在链表中找到值为key的节点e，直接设置e.val = value即可
                                //如果发现相同的key，则判断是否需要进行值的覆盖
                                if (e.hash == hash &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    oldVal = e.val;
                                    if (!onlyIfAbsent)//默认情况下，直接覆盖旧的值
                                        e.val = value;
                                    break;
                                }
                                // 如果没有找到值为key的节点，直接新建Node并加入链表即可
                                //一直遍历到链表的最末端，直接把新的值加入到链表的最后面
                                Node<K, V> pred = e;
                                if ((e = e.next) == null) {//插入到链表末尾并跳出循环
                                    pred.next = new Node<K, V>(hash, key,
                                            value, null);
                                    break;
                                }
                            }
                        }
                        //如果当前的f节点是一颗红黑树
                        else if (f instanceof TreeBin) {// 如果首节点为TreeBin类型，说明为红黑树结构，执行putTreeVal操作
                            Node<K, V> p;
                            binCount = 2;
                            //则调用红黑树的插法插入新的
                            if ((p = ((TreeBin<K, V>) f).putTreeVal(hash, key,
                                    value)) != null) {
                                oldVal = p.val;//同样，如果值已经存在，则直接替换
                                if (!onlyIfAbsent)
                                    p.val = value;
                            }
                        }
                    }
                }

                // put 方法第四个阶段

                // 判断链表的长度是否已经达到临界值 8. 如果达到了临界值，这个时候会根据当前数组的长度
                // 来决定是扩容还是将链表转化为红黑树。 也就是说如果当前数组的长度小于 64，就会先扩容。
                // 否则，会把当前链表转化为红黑树
                if (binCount != 0) { //说明上面在做链表操作
                    // 如果节点数>＝8，那么转换链表结构为红黑树结构
                    //如果链表长度已经达到临界值8就需要把链表转换为树结构
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);//若length<64,直接tryPresize,两倍table.length;不转红黑树
                    if (oldVal != null) //如果val是被替换的，则返回替换之前的值
                        return oldVal;
                    break;
                }
            }
        }

        // put 方法第二阶段

        // 计数增加1，有可能触发transfer操作(扩容)
        //将当前 ConcurrentHashMap的元素数量加1，有可能触发 transfer操作（扩容）
        addCount(1L, binCount);
        return null;
    }

    /**
     * Copies all of the mappings from the specified map to this one.
     * These mappings replace any mappings that this map had for any of the
     * keys currently in the specified map.
     *
     * @param m mappings to be stored in this map
     */
    public void putAll(Map<? extends K, ? extends V> m) {
        tryPresize(m.size());
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet())
            putVal(e.getKey(), e.getValue(), false);
    }

    /**
     * Removes the key (and its corresponding value) from this map.
     * This method does nothing if the key is not in the map.
     *
     * @param key the key that needs to be removed
     * @return the previous value associated with {@code key}, or
     * {@code null} if there was no mapping for {@code key}
     * @throws NullPointerException if the specified key is null
     */
    public V remove(Object key) {
        return replaceNode(key, null, null);
    }

    /**
     * Implementation for the four public remove/replace methods:
     * Replaces node value with v, conditional upon match of cv if
     * non-null.  If resulting value is null, delete.
     */
    final V replaceNode(Object key, V value, Object cv) {
        int hash = spread(key.hashCode());
        for (Node<K, V>[] tab = table; ; ) {
            Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0 ||
                    (f = tabAt(tab, i = (n - 1) & hash)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                V oldVal = null;
                boolean validated = false;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            validated = true;
                            for (Node<K, V> e = f, pred = null; ; ) {
                                K ek;
                                if (e.hash == hash &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    V ev = e.val;
                                    if (cv == null || cv == ev ||
                                            (ev != null && cv.equals(ev))) {
                                        oldVal = ev;
                                        if (value != null)
                                            e.val = value;
                                        else if (pred != null)
                                            pred.next = e.next;
                                        else
                                            setTabAt(tab, i, e.next);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        } else if (f instanceof TreeBin) {
                            validated = true;
                            TreeBin<K, V> t = (TreeBin<K, V>) f;
                            TreeNode<K, V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(hash, key, null)) != null) {
                                V pv = p.val;
                                if (cv == null || cv == pv ||
                                        (pv != null && cv.equals(pv))) {
                                    oldVal = pv;
                                    if (value != null)
                                        p.val = value;
                                    else if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                if (validated) {
                    if (oldVal != null) {
                        if (value == null)
                            addCount(-1L, -1);
                        return oldVal;
                    }
                    break;
                }
            }
        }
        return null;
    }

    /**
     * Removes all of the mappings from this map.
     */
    public void clear() {
        long delta = 0L; // negative number of deletions
        int i = 0;
        Node<K, V>[] tab = table;
        while (tab != null && i < tab.length) {
            int fh;
            Node<K, V> f = tabAt(tab, i);
            if (f == null)
                ++i;
            else if ((fh = f.hash) == MOVED) {
                tab = helpTransfer(tab, f);
                i = 0; // restart
            } else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        Node<K, V> p = (fh >= 0 ? f :
                                (f instanceof TreeBin) ?
                                        ((TreeBin<K, V>) f).first : null);
                        while (p != null) {
                            --delta;
                            p = p.next;
                        }
                        setTabAt(tab, i++, null);
                    }
                }
            }
        }
        if (delta != 0L)
            addCount(delta, -1);
    }

    /**
     * Returns a {@link Set} view of the keys contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa. The set supports element
     * removal, which removes the corresponding mapping from this map,
     * via the {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll}, and {@code clear}
     * operations.  It does not support the {@code add} or
     * {@code addAll} operations.
     * <p>
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     * <p>
     * <p>The view's {@code spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#DISTINCT}, and {@link Spliterator#NONNULL}.
     *
     * @return the set view
     */
    public KeySetView<K, V> keySet() {
        KeySetView<K, V> ks;
        return (ks = keySet) != null ? ks : (keySet = new KeySetView<K, V>(this, null));
    }

    /**
     * Returns a {@link Collection} view of the values contained in this map.
     * The collection is backed by the map, so changes to the map are
     * reflected in the collection, and vice-versa.  The collection
     * supports element removal, which removes the corresponding
     * mapping from this map, via the {@code Iterator.remove},
     * {@code Collection.remove}, {@code removeAll},
     * {@code retainAll}, and {@code clear} operations.  It does not
     * support the {@code add} or {@code addAll} operations.
     * <p>
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     * <p>
     * <p>The view's {@code spliterator} reports {@link Spliterator#CONCURRENT}
     * and {@link Spliterator#NONNULL}.
     *
     * @return the collection view
     */
    public Collection<V> values() {
        ValuesView<K, V> vs;
        return (vs = values) != null ? vs : (values = new ValuesView<K, V>(this));
    }

    /**
     * Returns a {@link Set} view of the mappings contained in this map.
     * The set is backed by the map, so changes to the map are
     * reflected in the set, and vice-versa.  The set supports element
     * removal, which removes the corresponding mapping from the map,
     * via the {@code Iterator.remove}, {@code Set.remove},
     * {@code removeAll}, {@code retainAll}, and {@code clear}
     * operations.
     * <p>
     * <p>The view's iterators and spliterators are
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     * <p>
     * <p>The view's {@code spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#DISTINCT}, and {@link Spliterator#NONNULL}.
     *
     * @return the set view
     */
    public Set<Map.Entry<K, V>> entrySet() {
        EntrySetView<K, V> es;
        return (es = entrySet) != null ? es : (entrySet = new EntrySetView<K, V>(this));
    }

    /**
     * Returns the hash code value for this {@link Map}, i.e.,
     * the sum of, for each key-value pair in the map,
     * {@code key.hashCode() ^ value.hashCode()}.
     *
     * @return the hash code value for this map
     */
    public int hashCode() {
        int h = 0;
        Node<K, V>[] t;
        if ((t = table) != null) {
            Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
            for (Node<K, V> p; (p = it.advance()) != null; )
                h += p.key.hashCode() ^ p.val.hashCode();
        }
        return h;
    }

    /**
     * Returns a string representation of this map.  The string
     * representation consists of a list of key-value mappings (in no
     * particular order) enclosed in braces ("{@code {}}").  Adjacent
     * mappings are separated by the characters {@code ", "} (comma
     * and space).  Each key-value mapping is rendered as the key
     * followed by an equals sign ("{@code =}") followed by the
     * associated value.
     *
     * @return a string representation of this map
     */
    public String toString() {
        Node<K, V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        Traverser<K, V> it = new Traverser<K, V>(t, f, 0, f);
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        Node<K, V> p;
        if ((p = it.advance()) != null) {
            for (; ; ) {
                K k = p.key;
                V v = p.val;
                sb.append(k == this ? "(this Map)" : k);
                sb.append('=');
                sb.append(v == this ? "(this Map)" : v);
                if ((p = it.advance()) == null)
                    break;
                sb.append(',').append(' ');
            }
        }
        return sb.append('}').toString();
    }

    /**
     * Compares the specified object with this map for equality.
     * Returns {@code true} if the given object is a map with the same
     * mappings as this map.  This operation may return misleading
     * results if either map is concurrently modified during execution
     * of this method.
     *
     * @param o object to be compared for equality with this map
     * @return {@code true} if the specified object is equal to this map
     */
    public boolean equals(Object o) {
        if (o != this) {
            if (!(o instanceof Map))
                return false;
            Map<?, ?> m = (Map<?, ?>) o;
            Node<K, V>[] t;
            int f = (t = table) == null ? 0 : t.length;
            Traverser<K, V> it = new Traverser<K, V>(t, f, 0, f);
            for (Node<K, V> p; (p = it.advance()) != null; ) {
                V val = p.val;
                Object v = m.get(p.key);
                if (v == null || (v != val && !v.equals(val)))
                    return false;
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object mk, mv, v;
                if ((mk = e.getKey()) == null ||
                        (mv = e.getValue()) == null ||
                        (v = get(mk)) == null ||
                        (mv != v && !mv.equals(v)))
                    return false;
            }
        }
        return true;
    }

    /**
     * Stripped-down version of helper class used in previous version,
     * declared for the sake of serialization compatibility
     */
    static class Segment<K, V> extends ReentrantLock implements Serializable {
        private static final long serialVersionUID = 2249069246763182397L;
        final float loadFactor;

        Segment(float lf) {
            this.loadFactor = lf;
        }
    }

    /**
     * Saves the state of the {@code ConcurrentHashMap} instance to a
     * stream (i.e., serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData the key (Object) and value (Object)
     * for each key-value mapping, followed by a null pair.
     * The key-value mappings are emitted in no particular order.
     */
    private void writeObject(java.io.ObjectOutputStream s)
            throws java.io.IOException {
        // For serialization compatibility
        // Emulate segment calculation from previous version of this class
        int sshift = 0;
        int ssize = 1;
        while (ssize < DEFAULT_CONCURRENCY_LEVEL) {
            ++sshift;
            ssize <<= 1;
        }
        int segmentShift = 32 - sshift;
        int segmentMask = ssize - 1;
        @SuppressWarnings("unchecked")
        Segment<K, V>[] segments = (Segment<K, V>[])
                new Segment<?, ?>[DEFAULT_CONCURRENCY_LEVEL];
        for (int i = 0; i < segments.length; ++i)
            segments[i] = new Segment<K, V>(LOAD_FACTOR);
        s.putFields().put("segments", segments);
        s.putFields().put("segmentShift", segmentShift);
        s.putFields().put("segmentMask", segmentMask);
        s.writeFields();

        Node<K, V>[] t;
        if ((t = table) != null) {
            Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
            for (Node<K, V> p; (p = it.advance()) != null; ) {
                s.writeObject(p.key);
                s.writeObject(p.val);
            }
        }
        s.writeObject(null);
        s.writeObject(null);
        segments = null; // throw away
    }

    /**
     * Reconstitutes the instance from a stream (that is, deserializes it).
     *
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *                                could not be found
     * @throws java.io.IOException    if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
        /*
         * To improve performance in typical cases, we create nodes
         * while reading, then place in table once size is known.
         * However, we must also validate uniqueness and deal with
         * overpopulated bins while doing so, which requires
         * specialized versions of putVal mechanics.
         */
        sizeCtl = -1; // force exclusion for table construction
        s.defaultReadObject();
        long size = 0L;
        Node<K, V> p = null;
        for (; ; ) {
            @SuppressWarnings("unchecked")
            K k = (K) s.readObject();
            @SuppressWarnings("unchecked")
            V v = (V) s.readObject();
            if (k != null && v != null) {
                p = new Node<K, V>(spread(k.hashCode()), k, v, p);
                ++size;
            } else
                break;
        }
        if (size == 0L)
            sizeCtl = 0;
        else {
            int n;
            if (size >= (long) (MAXIMUM_CAPACITY >>> 1))
                n = MAXIMUM_CAPACITY;
            else {
                int sz = (int) size;
                n = tableSizeFor(sz + (sz >>> 1) + 1);
            }
            @SuppressWarnings("unchecked")
            Node<K, V>[] tab = (Node<K, V>[]) new Node<?, ?>[n];
            int mask = n - 1;
            long added = 0L;
            while (p != null) {
                boolean insertAtFront;
                Node<K, V> next = p.next, first;
                int h = p.hash, j = h & mask;
                if ((first = tabAt(tab, j)) == null)
                    insertAtFront = true;
                else {
                    K k = p.key;
                    if (first.hash < 0) {
                        TreeBin<K, V> t = (TreeBin<K, V>) first;
                        if (t.putTreeVal(h, k, p.val) == null)
                            ++added;
                        insertAtFront = false;
                    } else {
                        int binCount = 0;
                        insertAtFront = true;
                        Node<K, V> q;
                        K qk;
                        for (q = first; q != null; q = q.next) {
                            if (q.hash == h &&
                                    ((qk = q.key) == k ||
                                            (qk != null && k.equals(qk)))) {
                                insertAtFront = false;
                                break;
                            }
                            ++binCount;
                        }
                        if (insertAtFront && binCount >= TREEIFY_THRESHOLD) {
                            insertAtFront = false;
                            ++added;
                            p.next = first;
                            TreeNode<K, V> hd = null, tl = null;
                            for (q = p; q != null; q = q.next) {
                                TreeNode<K, V> t = new TreeNode<K, V>
                                        (q.hash, q.key, q.val, null, null);
                                if ((t.prev = tl) == null)
                                    hd = t;
                                else
                                    tl.next = t;
                                tl = t;
                            }
                            setTabAt(tab, j, new TreeBin<K, V>(hd));
                        }
                    }
                }
                if (insertAtFront) {
                    ++added;
                    p.next = first;
                    setTabAt(tab, j, p);
                }
                p = next;
            }
            table = tab;
            sizeCtl = n - (n >>> 2);
            baseCount = added;
        }
    }

    // ConcurrentMap methods

    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     * or {@code null} if there was no mapping for the key
     * @throws NullPointerException if the specified key or value is null
     */
    public V putIfAbsent(K key, V value) {
        return putVal(key, value, true);
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the specified key is null
     */
    public boolean remove(Object key, Object value) {
        if (key == null)
            throw new NullPointerException();
        return value != null && replaceNode(key, null, value) != null;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if any of the arguments are null
     */
    public boolean replace(K key, V oldValue, V newValue) {
        if (key == null || oldValue == null || newValue == null)
            throw new NullPointerException();
        return replaceNode(key, newValue, oldValue) != null;
    }

    /**
     * {@inheritDoc}
     *
     * @return the previous value associated with the specified key,
     * or {@code null} if there was no mapping for the key
     * @throws NullPointerException if the specified key or value is null
     */
    public V replace(K key, V value) {
        if (key == null || value == null)
            throw new NullPointerException();
        return replaceNode(key, value, null);
    }

    // Overrides of JDK8+ Map extension method defaults

    /**
     * Returns the value to which the specified key is mapped, or the
     * given default value if this map contains no mapping for the
     * key.
     *
     * @param key          the key whose associated value is to be returned
     * @param defaultValue the value to return if this map contains
     *                     no mapping for the given key
     * @return the mapping for the key, if present; else the default value
     * @throws NullPointerException if the specified key is null
     */
    public V getOrDefault(Object key, V defaultValue) {
        V v;
        return (v = get(key)) == null ? defaultValue : v;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        Node<K, V>[] t;
        if ((t = table) != null) {
            Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
            for (Node<K, V> p; (p = it.advance()) != null; ) {
                action.accept(p.key, p.val);
            }
        }
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (function == null) throw new NullPointerException();
        Node<K, V>[] t;
        if ((t = table) != null) {
            Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
            for (Node<K, V> p; (p = it.advance()) != null; ) {
                V oldValue = p.val;
                for (K key = p.key; ; ) {
                    V newValue = function.apply(key, oldValue);
                    if (newValue == null)
                        throw new NullPointerException();
                    if (replaceNode(key, newValue, oldValue) != null ||
                            (oldValue = get(key)) == null)
                        break;
                }
            }
        }
    }

    /**
     * If the specified key is not already associated with a value,
     * attempts to compute its value using the given mapping function
     * and enters it into this map unless {@code null}.  The entire
     * method invocation is performed atomically, so the function is
     * applied at most once per key.  Some attempted update operations
     * on this map by other threads may be blocked while computation
     * is in progress, so the computation should be short and simple,
     * and must not attempt to update any other mappings of this map.
     *
     * @param key             key with which the specified value is to be associated
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with
     * the specified key, or null if the computed value is null
     * @throws NullPointerException  if the specified key or mappingFunction
     *                               is null
     * @throws IllegalStateException if the computation detectably
     *                               attempts a recursive update to this map that would
     *                               otherwise never complete
     * @throws RuntimeException      or Error if the mappingFunction does so,
     *                               in which case the mapping is left unestablished
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int binCount = 0;
        for (Node<K, V>[] tab = table; ; ) {
            Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K, V> r = new ReservationNode<K, V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K, V> node = null;
                        try {
                            if ((val = mappingFunction.apply(key)) != null)
                                node = new Node<K, V>(h, key, val, null);
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            } else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                boolean added = false;
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K, V> e = f; ; ++binCount) {
                                K ek;
                                V ev;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = e.val;
                                    break;
                                }
                                Node<K, V> pred = e;
                                if ((e = e.next) == null) {
                                    if ((val = mappingFunction.apply(key)) != null) {
                                        added = true;
                                        pred.next = new Node<K, V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        } else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K, V> t = (TreeBin<K, V>) f;
                            TreeNode<K, V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(h, key, null)) != null)
                                val = p.val;
                            else if ((val = mappingFunction.apply(key)) != null) {
                                added = true;
                                t.putTreeVal(h, key, val);
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (!added)
                        return val;
                    break;
                }
            }
        }
        if (val != null)
            addCount(1L, binCount);
        return val;
    }

    /**
     * If the value for the specified key is present, attempts to
     * compute a new mapping given the key and its current mapped
     * value.  The entire method invocation is performed atomically.
     * Some attempted update operations on this map by other threads
     * may be blocked while computation is in progress, so the
     * computation should be short and simple, and must not attempt to
     * update any other mappings of this map.
     *
     * @param key               key with which a value may be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException  if the specified key or remappingFunction
     *                               is null
     * @throws IllegalStateException if the computation detectably
     *                               attempts a recursive update to this map that would
     *                               otherwise never complete
     * @throws RuntimeException      or Error if the remappingFunction does so,
     *                               in which case the mapping is unchanged
     */
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K, V>[] tab = table; ; ) {
            Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null)
                break;
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K, V> e = f, pred = null; ; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K, V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null)
                                    break;
                            }
                        } else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K, V> t = (TreeBin<K, V>) f;
                            TreeNode<K, V> r, p;
                            if ((r = t.root) != null &&
                                    (p = r.findTreeNode(h, key, null)) != null) {
                                val = remappingFunction.apply(key, p.val);
                                if (val != null)
                                    p.val = val;
                                else {
                                    delta = -1;
                                    if (t.removeTreeNode(p))
                                        setTabAt(tab, i, untreeify(t.first));
                                }
                            }
                        }
                    }
                }
                if (binCount != 0)
                    break;
            }
        }
        if (delta != 0)
            addCount((long) delta, binCount);
        return val;
    }

    /**
     * Attempts to compute a mapping for the specified key and its
     * current mapped value (or {@code null} if there is no current
     * mapping). The entire method invocation is performed atomically.
     * Some attempted update operations on this map by other threads
     * may be blocked while computation is in progress, so the
     * computation should be short and simple, and must not attempt to
     * update any other mappings of this Map.
     *
     * @param key               key with which the specified value is to be associated
     * @param remappingFunction the function to compute a value
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException  if the specified key or remappingFunction
     *                               is null
     * @throws IllegalStateException if the computation detectably
     *                               attempts a recursive update to this map that would
     *                               otherwise never complete
     * @throws RuntimeException      or Error if the remappingFunction does so,
     *                               in which case the mapping is unchanged
     */
    public V compute(K key,
                     BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K, V>[] tab = table; ; ) {
            Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                Node<K, V> r = new ReservationNode<K, V>();
                synchronized (r) {
                    if (casTabAt(tab, i, null, r)) {
                        binCount = 1;
                        Node<K, V> node = null;
                        try {
                            if ((val = remappingFunction.apply(key, null)) != null) {
                                delta = 1;
                                node = new Node<K, V>(h, key, val, null);
                            }
                        } finally {
                            setTabAt(tab, i, node);
                        }
                    }
                }
                if (binCount != 0)
                    break;
            } else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K, V> e = f, pred = null; ; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(key, e.val);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K, V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    val = remappingFunction.apply(key, null);
                                    if (val != null) {
                                        delta = 1;
                                        pred.next =
                                                new Node<K, V>(h, key, val, null);
                                    }
                                    break;
                                }
                            }
                        } else if (f instanceof TreeBin) {
                            binCount = 1;
                            TreeBin<K, V> t = (TreeBin<K, V>) f;
                            TreeNode<K, V> r, p;
                            if ((r = t.root) != null)
                                p = r.findTreeNode(h, key, null);
                            else
                                p = null;
                            V pv = (p == null) ? null : p.val;
                            val = remappingFunction.apply(key, pv);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            } else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long) delta, binCount);
        return val;
    }

    /**
     * If the specified key is not already associated with a
     * (non-null) value, associates it with the given value.
     * Otherwise, replaces the value with the results of the given
     * remapping function, or removes if {@code null}. The entire
     * method invocation is performed atomically.  Some attempted
     * update operations on this map by other threads may be blocked
     * while computation is in progress, so the computation should be
     * short and simple, and must not attempt to update any other
     * mappings of this Map.
     *
     * @param key               key with which the specified value is to be associated
     * @param value             the value to use if absent
     * @param remappingFunction the function to recompute a value if present
     * @return the new value associated with the specified key, or null if none
     * @throws NullPointerException if the specified key or the
     *                              remappingFunction is null
     * @throws RuntimeException     or Error if the remappingFunction does so,
     *                              in which case the mapping is unchanged
     */
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null)
            throw new NullPointerException();
        int h = spread(key.hashCode());
        V val = null;
        int delta = 0;
        int binCount = 0;
        for (Node<K, V>[] tab = table; ; ) {
            Node<K, V> f;
            int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            else if ((f = tabAt(tab, i = (n - 1) & h)) == null) {
                if (casTabAt(tab, i, null, new Node<K, V>(h, key, value, null))) {
                    delta = 1;
                    val = value;
                    break;
                }
            } else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            else {
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K, V> e = f, pred = null; ; ++binCount) {
                                K ek;
                                if (e.hash == h &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    val = remappingFunction.apply(e.val, value);
                                    if (val != null)
                                        e.val = val;
                                    else {
                                        delta = -1;
                                        Node<K, V> en = e.next;
                                        if (pred != null)
                                            pred.next = en;
                                        else
                                            setTabAt(tab, i, en);
                                    }
                                    break;
                                }
                                pred = e;
                                if ((e = e.next) == null) {
                                    delta = 1;
                                    val = value;
                                    pred.next =
                                            new Node<K, V>(h, key, val, null);
                                    break;
                                }
                            }
                        } else if (f instanceof TreeBin) {
                            binCount = 2;
                            TreeBin<K, V> t = (TreeBin<K, V>) f;
                            TreeNode<K, V> r = t.root;
                            TreeNode<K, V> p = (r == null) ? null :
                                    r.findTreeNode(h, key, null);
                            val = (p == null) ? value :
                                    remappingFunction.apply(p.val, value);
                            if (val != null) {
                                if (p != null)
                                    p.val = val;
                                else {
                                    delta = 1;
                                    t.putTreeVal(h, key, val);
                                }
                            } else if (p != null) {
                                delta = -1;
                                if (t.removeTreeNode(p))
                                    setTabAt(tab, i, untreeify(t.first));
                            }
                        }
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    break;
                }
            }
        }
        if (delta != 0)
            addCount((long) delta, binCount);
        return val;
    }

    // Hashtable legacy methods

    /**
     * Legacy method testing if some key maps into the specified value
     * in this table.  This method is identical in functionality to
     * {@link #containsValue(Object)}, and exists solely to ensure
     * full compatibility with class {@link java.util.Hashtable},
     * which supported this method prior to introduction of the
     * Java Collections framework.
     *
     * @param value a value to search for
     * @return {@code true} if and only if some key maps to the
     * {@code value} argument in this table as
     * determined by the {@code equals} method;
     * {@code false} otherwise
     * @throws NullPointerException if the specified value is null
     */
    public boolean contains(Object value) {
        return containsValue(value);
    }

    /**
     * Returns an enumeration of the keys in this table.
     *
     * @return an enumeration of the keys in this table
     * @see #keySet()
     */
    public Enumeration<K> keys() {
        Node<K, V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new KeyIterator<K, V>(t, f, 0, f, this);
    }

    /**
     * Returns an enumeration of the values in this table.
     *
     * @return an enumeration of the values in this table
     * @see #values()
     */
    public Enumeration<V> elements() {
        Node<K, V>[] t;
        int f = (t = table) == null ? 0 : t.length;
        return new ValueIterator<K, V>(t, f, 0, f, this);
    }

    // ConcurrentHashMap-only methods

    /**
     * Returns the number of mappings. This method should be used
     * instead of {@link #size} because a ConcurrentHashMap may
     * contain more mappings than can be represented as an int. The
     * value returned is an estimate; the actual count may differ if
     * there are concurrent insertions or removals.
     *
     * @return the number of mappings
     * @since 1.8
     */
    public long mappingCount() {
        long n = sumCount();
        return (n < 0L) ? 0L : n; // ignore transient negative values
    }

    /**
     * Creates a new {@link Set} backed by a ConcurrentHashMap
     * from the given type to {@code Boolean.TRUE}.
     *
     * @param <K> the element type of the returned set
     * @return the new set
     * @since 1.8
     */
    public static <K> KeySetView<K, Boolean> newKeySet() {
        return new KeySetView<K, Boolean>
                (new ConcurrentHashMap<K, Boolean>(), Boolean.TRUE);
    }

    /**
     * Creates a new {@link Set} backed by a ConcurrentHashMap
     * from the given type to {@code Boolean.TRUE}.
     *
     * @param initialCapacity The implementation performs internal
     *                        sizing to accommodate this many elements.
     * @param <K>             the element type of the returned set
     * @return the new set
     * @throws IllegalArgumentException if the initial capacity of
     *                                  elements is negative
     * @since 1.8
     */
    public static <K> KeySetView<K, Boolean> newKeySet(int initialCapacity) {
        return new KeySetView<K, Boolean>
                (new ConcurrentHashMap<K, Boolean>(initialCapacity), Boolean.TRUE);
    }

    /**
     * Returns a {@link Set} view of the keys in this map, using the
     * given common mapped value for any additions (i.e., {@link
     * Collection#add} and {@link Collection#addAll(Collection)}).
     * This is of course only appropriate if it is acceptable to use
     * the same value for all additions from this view.
     *
     * @param mappedValue the mapped value to use for any additions
     * @return the set view
     * @throws NullPointerException if the mappedValue is null
     */
    public KeySetView<K, V> keySet(V mappedValue) {
        if (mappedValue == null)
            throw new NullPointerException();
        return new KeySetView<K, V>(this, mappedValue);
    }

    /* ---------------- Special Nodes -------------- */

    /**
     * A node inserted at head of bins during transfer operations.
     */
    static final class ForwardingNode<K, V> extends Node<K, V> {
        final Node<K, V>[] nextTable;

        ForwardingNode(Node<K, V>[] tab) {
            super(MOVED, null, null, null);
            this.nextTable = tab;
        }

        Node<K, V> find(int h, Object k) {
            // loop to avoid arbitrarily deep recursion on forwarding nodes
            outer:
            for (Node<K, V>[] tab = nextTable; ; ) {
                Node<K, V> e;
                int n;
                if (k == null || tab == null || (n = tab.length) == 0 ||
                        (e = tabAt(tab, (n - 1) & h)) == null)
                    return null;
                for (; ; ) {
                    int eh;
                    K ek;
                    if ((eh = e.hash) == h &&
                            ((ek = e.key) == k || (ek != null && k.equals(ek))))
                        return e;
                    if (eh < 0) {
                        if (e instanceof ForwardingNode) {
                            tab = ((ForwardingNode<K, V>) e).nextTable;
                            continue outer;
                        } else
                            return e.find(h, k);
                    }
                    if ((e = e.next) == null)
                        return null;
                }
            }
        }
    }

    /**
     * A place-holder node used in computeIfAbsent and compute
     */
    static final class ReservationNode<K, V> extends Node<K, V> {
        ReservationNode() {
            super(RESERVED, null, null, null);
        }

        Node<K, V> find(int h, Object k) {
            return null;
        }
    }

    /* ---------------- Table Initialization and Resizing -------------- */

    /**
     * Returns the stamp bits for resizing a table of size n.
     * Must be negative when shifted left by RESIZE_STAMP_SHIFT.
     */
    //这块逻辑要理解起来，也有一点复杂。
    // resizeStamp 用来生成一个和扩容有关的扩容戳，具体有什么作用呢？我们基于它的实现来
    // 做一个分析
    static final int resizeStamp(int n) {
        return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
    }

    /**
     * Initializes table, using the size recorded in sizeCtl.
     */
    //数组初始化方法，这个方法比较简单，就是初始化一个合适大小的数组
    // sizeCtl 这个要单独说一下，如果没搞懂这个属性的意义，可能会被搞晕
    // 这个标志是在 Node 数组初始化或者扩容的时候的一个控制位标识，负数代表正在进行初始
    // 化或者扩容操作
    // -1 代表正在初始化
    // -N 代表有 N-1 有n-1个线程正在进行扩容操作，这里不是简单的理解成 n 个线程， sizeCtl 就是-N,这块后续在讲扩容的时候会说明
    // 0 标识 Node 数组还没有被初始化，正数代表初始化或者下一次扩容的大小
    private final Node<K, V>[] initTable() {
        Node<K, V>[] tab;
        int sc;
        while ((tab = table) == null || tab.length == 0) {
            if ((sc = sizeCtl) < 0)//被其他线程抢占了初始化的操作，则直接让出自己的CPU时间片
                Thread.yield(); // lost initialization race; just spin
            // 通过cas操作，将 sizes1替换为-1，标识当前线程抢占到了初始化资格
            else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                try {
                    if ((tab = table) == null || tab.length == 0) {
                        int n = (sc > 0) ? sc : DEFAULT_CAPACITY;//默认初始容量为16
                        @SuppressWarnings("unchecked")
                        //初始化数组，长度为16，或者初始化在构造 ConcurrentHashMap的时候传入的长度
                        Node<K, V>[] nt = (Node<K, V>[]) new Node<?, ?>[n];
                        table = tab = nt;//将这个数组赋值给tab1e
                        sc = n - (n >>> 2);//计算下次扩容的大小，实际就是当前容量的0.75倍，这里使用了右移来计算
                    //    无符号位右移和相当于符号右移(>>)，只不过高位不用1和0 左乘右除，n-n/2*2=(3/4)*n
                    }
                } finally {
                    sizeCtl = sc;//设置 sizeCtl为sc，如果默认是16的话，那么这个时候sc=16*0.75=12
                }
                break;
            }
        }
        return tab;
    }

    /**
     * Adds to count, and if table is too small and not already
     * resizing, initiates transfer. If already resizing, helps
     * perform transfer if work is available.  Rechecks occupancy
     * after a transfer to see if another resize is already needed
     * because resizings are lagging additions.
     *
     * @param x     the count to add
     * @param check if <0, don't check resize, if <= 1 only check if uncontended
     */
    //在putVal方法执行完成以后， 会通过addCount来增加ConcurrentHashMap中的元素个数，
    // 并且还会可能触发扩容操作。这里会有两个非常经典的设计
    // 1. 高并发下的扩容
    // 2. 如何保证 addCount 的数据安全性以及性能

    //在 putVal 最后调用 addCount 的时候，传递了两个参数，分别是 1 和 binCount(链表长度)，
    // 看看 addCount 方法里面做了什么操作
    // x 表示这次需要在表中增加的元素个数， check 参数表示是否需要进行扩容检查，大于等于 0
    // 都需要进行检查
    private final void addCount(long x, int check) {
        //CounterCell[]
        //     记录元素个数
        //     为什么用数组？高并发下，用自旋的方式竞争激烈，除了拿到锁的那个，剩下都在空转，影响想能，所以采用分片的方式记录大小。
        CounterCell[] as;
        long b, s;
    //     判断 countercells是否为空
    //     1. 如果为空，就通过cas操作尝试修改 basecount变量，对这个变量进行原子累加操作
    // （做这个操作的意义是：如果在没有竞争的情况下，仍然采用 baseCount来记录元素个数
    //     2如果cas失败说明存在竞争，这个时候不能再采用 basecount来累加，而是通过Countercell来记录
        if ((as = counterCells) != null ||
                !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
            CounterCell a;
            long v;
            int m;
            boolean uncontended = true;//是否冲突标识，默认为没有冲突
            // 这里有几个判断
            // 1.计数表为空则直接调用fullAddCount
            // 2.从计数表中随机取出一个数组的位置为空，直接调用full AddCount
            // 3.通过CAS修改 Countercell随机位置的值，如果修改失败说明出现并发情况（这里又用到了一种巧妙的方法），
            // 调用fullAddCount Random在线程并发的时候会有性能问题以及可能会产生相同的随机数，
            // ThreadlocalRandom.geoprobe可以解决这个问题，并且性能要比 Random高
            if (as == null || (m = as.length - 1) < 0 ||
                    (a = as[ThreadLocalRandom.getProbe() & m]) == null ||
                    !(uncontended =
                            U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))) {
                fullAddCount(x, uncontended);//执行 fullAddCount 方法
                return;
            }
            if (check <= 1)//执行 fullAddCount 方法
                return;
            s = sumCount();//统计 ConcurrentHashMap 元素个数
        }
        //判断是否需要扩容，也就是当更新后的键值对总数 baseCount >= 阈值 sizeCtl 时，进行
        // rehash，这里面会有两个逻辑。
        // 1. 如果当前正在处于扩容阶段，则当前线程会加入并且协助扩容
        // 2. 如果当前没有在扩容，则直接触发扩容操作
        if (check >= 0) {//如果 bincount>=0，标识需要检查扩容
            Node<K, V>[] tab, nt;
            int n, sc;
            //s标识集合大小，如果集合大小大于或等于扩容阈值（默认值的0.75）
            //并且ta1e不为空并且table的长度小于最大容量
            while (s >= (long) (sc = sizeCtl) && (tab = table) != null &&
                    (n = tab.length) < MAXIMUM_CAPACITY) {
                int rs = resizeStamp(n);//这里是生成一个唯一的扩容戳，这个是干嘛用的呢？且听我慢慢分析
                if (sc < 0) {
                    //sc<0，也就是 sizectl<0，说明已经有别的线程正在扩容了
                    //这5个条件只要有一个条件为true，说明当前线程不能帮助进行此次的扩容直接跳出循环
                    //sc>>> RESIZE STAMP SHIFT！=rs表示比较高 RESIZE STAMP BITS位生成戳和rs是否相等，相同
                    //sc=rs+1表示扩容结束
                    //sc==rs+ MAX RESIZERS表示帮助线程线程已经达到最大值了
                    //nt= netlAb1e->表示扩容已经结束
                    // transferlndex<=0表示所有的 transfer任务都被领取完了，没有剩余的hash桶来给自己自己好这个线程来做 transfer
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                            sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                            transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))//当前线程尝试帮助此次扩容，如果成功，则调用 transfer
                        transfer(tab, nt);
                }
                //如果当前没有线程在扩容，那么rs肯定是一个正数，通过rs<< RESIZE STAMP SHIFT将sc设置为一个负数，+2表示有一个线程在执行扩容
                else if (U.compareAndSwapInt(this, SIZECTL, sc,
                        (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
                s = sumCount();//重新计数，判断是否需要开启下一轮扩容
            }
        }
    }

    /**
     * Helps transfer if a resize is in progress.
     */
    final Node<K, V>[] helpTransfer(Node<K, V>[] tab, Node<K, V> f) {
        Node<K, V>[] nextTab;
        int sc;
        // 判断此时是否仍然在执行扩容,nextTab=null 的时候说明扩容已经结束了
        if (tab != null && (f instanceof ForwardingNode) &&
                (nextTab = ((ForwardingNode<K, V>) f).nextTable) != null) {
            int rs = resizeStamp(tab.length);//生成扩容戳
            while (nextTab == nextTable && table == tab &&
                    (sc = sizeCtl) < 0) {//说明扩容还未完成的情况下不断循环来尝试将当前
                //线程加入到扩容操作中
                //下面部分的整个代码表示扩容结束，直接退出循环
                // transferindex<=0表示所有的Node都已经分配了线程
                //sc=rs+ MAX RESIZERS表示扩容线程数达到最大扩容线程数
                //sc>>> RESIZE STAMP SHIFT！=rs，如果在同一轮扩容中，那么sc无符号
                //右移比较高位和rs的值，那么应该是相等的。如果不相等，说明扩容结束了
                //c==s+1表示扩容结束
                if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                        sc == rs + MAX_RESIZERS || transferIndex <= 0)
                    break;//跳出循环
                if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) {//在低16位上增加扩容线程数
                    transfer(tab, nextTab);//帮助扩容
                    break;
                }
            }
            return nextTab;//返回新的数组
        }
        return table;
    }

    /**
     * Tries to presize table to accommodate the given number of elements.
     *
     * @param size number of elements (doesn't need to be perfectly accurate)
     */
    private final void tryPresize(int size) {
        //对size进行修复，主要目的是防止传入的值不是一个2次幂的整数，然后通过tableSizeFor来讲入参转化为离该整数最近的2次幂
        int c = (size >= (MAXIMUM_CAPACITY >>> 1)) ? MAXIMUM_CAPACITY :
                tableSizeFor(size + (size >>> 1) + 1);
        int sc;
        while ((sc = sizeCtl) >= 0) {
            Node<K, V>[] tab = table;
            int n;
            //下面这段代码和 instAble是一样的，如果 table没有初始化，则开始初始化
            if (tab == null || (n = tab.length) == 0) {
                n = (sc > c) ? sc : c;
                if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
                    try {
                        if (table == tab) {
                            @SuppressWarnings("unchecked")
                            Node<K, V>[] nt = (Node<K, V>[]) new Node<?, ?>[n];
                            table = nt;
                            sc = n - (n >>> 2);//0.75
                        }
                    } finally {
                        sizeCtl = sc;
                    }
                }
            } else if (c <= sc || n >= MAXIMUM_CAPACITY)
                break;
            else if (tab == table) {//这段代码和 addcount后部分代码是一样的，做辅助扩容操作
                int rs = resizeStamp(n);
                if (sc < 0) {
                    Node<K, V>[] nt;
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs || sc == rs + 1 ||
                            sc == rs + MAX_RESIZERS || (nt = nextTable) == null ||
                            transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                } else if (U.compareAndSwapInt(this, SIZECTL, sc,
                        (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
            }
        }
    }

    /**
     * Moves and/or copies the nodes in each bin to new table. See
     * above for explanation.
     */
    //扩容是 ConcurrentHashMap 的精华之一， 扩容操作的核心在于数据的转移，在单线程环境
    // 下数据的转移很简单，无非就是把旧数组中的数据迁移到新的数组。但是这在多线程环境下，
    // 在扩容的时候其他线程也可能正在添加元素，这时又触发了扩容怎么办？ 可能大家想到的第
    // 一个解决方案是加互斥锁，把转移过程锁住，虽然是可行的解决方案，但是会带来较大的性
    // 能开销。因为互斥锁会导致所有访问临界区的线程陷入到阻塞状态，持有锁的线程耗时越长，
    // 其他竞争线程就会一直被阻塞，导致吞吐量较低。而且还可能导致死锁。

    // 而 ConcurrentHashMap 并没有直接加锁，而是采用 CAS 实现无锁的并发同步策略，最精华
    // 的部分是它可以利用多线程来进行协同扩容

    // 简单来说，它把 Node 数组当作多个线程之间共享的任务队列，然后通过维护一个指针来划
    // 分每个线程锁负责的区间，每个线程通过区间逆向遍历来实现扩容，一个已经迁移完的
    // bucket 会被替换为一个 ForwardingNode 节点，标记当前 bucket 已经被其他线程迁移完了。
    // 接下来分析一下它的源码实现

    // 1、 fwd:这个类是个标识类，用于指向新表用的，其他线程遇到这个类会主动跳过这个类，因
    // 为这个类要么就是扩容迁移正在进行，要么就是已经完成扩容迁移，也就是这个类要保证线
    // 程安全，再进行操作。
    // 2、 advance:这个变量是用于提示代码是否进行推进处理，也就是当前桶处理完，处理下一个
    // 桶的标识
    // 3、 finishing:这个变量用于提示扩容是否结束用的

    //stride处理的长度
    //transferIndex转移时候的下标
    private final void transfer(Node<K, V>[] tab, Node<K, V>[] nextTab) {
        int n = tab.length, stride;
        //将（n>>>3相当于n/8）然后除以CPU核心数。如果得到的结果小于16，那么就使用16
        //这里的目的是让每个CPU处理的桶一样多，避免出现转移任务不均匀的现象，如果桶较少
        //的话，默认一个CPU（一个线程）处理16个桶，也就是长度为16的时候，扩容的时候只会有一线程来扩容

        //stride处理的长度
        if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
            stride = MIN_TRANSFER_STRIDE; // subdivide range
        // nextTab未初始化， netTab是用来扩容的node数组
        if (nextTab == null) {            // initiating
            try {
                @SuppressWarnings("unchecked")
                //新建一个n<<1原始 table大小的 netTab，也就是32
                Node<K, V>[] nt = (Node<K, V>[]) new Node<?, ?>[n << 1];
                nextTab = nt;//赋值给 netTab
            } catch (Throwable ex) {      // try to cope with OOME
                sizeCtl = Integer.MAX_VALUE; //扩容失败， sizeCtl使用int的最大值
                return;
            }
            nextTable = nextTab;//更新成员变量
            transferIndex = n;//更新转移下标，表示转移时的下标
        }
        int nextn = nextTab.length;//新的tab的长度
        //创建一个fwd节点，表示一个正在被迁移的Node，并且它的hash值为-1（ MOVED），也
        //就是前面我们在讲 putval方法的时候，会有一个判断 MOVED的逻辑。它的作用是用来占位，表示
        //原数组中位置i处的节点完成迁移以后，就会在i位置设置一个fwd来告诉其他线程这个位置已经
        //处理过了，具体后续还会在讲
        ForwardingNode<K, V> fwd = new ForwardingNode<K, V>(nextTab);
        //首次推进为true，如果等于true，说明需要再次推进一个下标（i--），反之，如果是
        //false，那么就不能推进下标，需要将当前的下标处理完毕才能继续推进
        boolean advance = true;
        //判断是否已经扩容完成，完成就 return，退出循环
        boolean finishing = false; // to ensure sweep before committing nextTab
        // 通过for自循环处理每个槽位中的链表元素，默认 advace为真，
        // 通过CAS设置transferindex属性值，并初始化i和 bound值，i指当前处理的槽位序号，
        // bound指需要处理的槽位边界，先处理槽位15的节点；
        for (int i = 0, bound = 0; ; ) {
            //这个循环使用CAS不断尝试为当前线程分配任务
            //直到分配成功或任务队列已经被全部分配完毕
            //如果当前线程已经被分配过 bucket区域
            //那么会通过--i指向下一个待处理 bucket然后退出该循环
            Node<K, V> f;
            int fh;
            //设置不同线程分工的区间
            while (advance) {
                int nextIndex, nextBound;
                //-i表示下一个待处理的 bucket，如果它>= bound，表示当前线程已经分配过bucket区域
                //bound指当前线程需要处理的槽位边界
                //在这了进行了--i操作
                if (--i >= bound || finishing)
                    advance = false;
                //在这里进行了赋值操作
                else if ((nextIndex = transferIndex) <= 0) {//表示所有 bucket已经被分配完毕
                    i = -1;
                    advance = false;
                }
                //通过cas来修改 TRANSFERINDEX，为当前线程分配任务，处理的节点区间为（nextBound, nextIndex）->（0， 15）
                //stride处理的长度
                else if (U.compareAndSwapInt
                        (this, TRANSFERINDEX, nextIndex,
                                nextBound = (nextIndex > stride ?
                                        nextIndex - stride : 0))) {//nextIndex - stride 32-16=16  -> nextIndex>stride-> nextBound=16 bound=16 i=32-1=31
                    //第一个线程nextBound=16 bound=16 i=32-1=31
                    //下一个线程进来的时候TRANSFERINDEX变成了16，就是转移的下标理解为 ，变成了16，nextindex变成了16 nextBound=0，i=15
                    //这么看是从后往前的
                    bound = nextBound;//0
                    i = nextIndex - 1;//15
                    advance = false;
                }
            }
            //i<0说明已经遍历完旧的数组，也就是当前线程已经处理完所有负责的 bucket
            if (i < 0 || i >= n || i + n >= nextn) {
                int sc;
                if (finishing) {//如果完成了扩容
                    nextTable = null;//删除成员变量
                    table = nextTab;//更新table数组
                    sizeCtl = (n << 1) - (n >>> 1);//更新阈值（32*0.75=24）
                    return;
                }
                // sizectl在迁移前会设置为（rs<< RESIZE STAMP SHIFT）+2（详细介绍点击这里）
                //然后，每增加一个线程参与迁移就会将 sizeCtl加1，
                //这里使用CAS操作对 sizeCtl加1，代表做完了属于自己的任务

                //每存在一个线程执行完扩容操作，就通过 cas 执行 sc-1。
                // 接着判断(sc-2) !=resizeStamp(n) << RESIZE_STAMP_SHIFT ; 如果相等，表示当前为整个扩
                // 容操作的 最后一个线程，那么意味着整个扩容操作就结束了；如果不想等，说明还得继续
                // 这么做的目的，一方面是防止不同扩容之间出现相同的 sizeCtl，另外一方面，还可以避免
                // sizeCtl 的 ABA 问题导致的扩容重叠的情况
                if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                    // 第一个扩容的线程，执行 transfer方法之前，会设置 sizectl
                    // （resizestamp（n）<< RESIZE STAMP SHIFT）+ 2）
                    // 后续帮其扩容的线程，执行 transfer方法之前，会设置 sizectl= sizectl+1
                    // 每一个退出 transfer的方法的线程，退出之前，会设置 sizectl= sizectl-1
                    // 那么最后一个线程退出时：必然有
                    // sc ==（resizeStamp（n）<< RESIZE STAMP SHIFT）+ 2）， Ep （sc-2
                    // resizestamp（n）<< RESIZE STAMP SHIFT
                    // 如果sc-2不等于标识符左移16位。如果他们相等了，说明没有线程在
                    // 帮助他们扩容了。也就是说，扩容结束了。
                    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                        return;
                    //如果相等，扩容结束了，更新 finising变量
                    finishing = advance = true;
                    //再次循环检查一下整张表
                    i = n; // recheck before commit
                }
            }
            //如果位置i处是空的，没有任何节点，那么放入刚刚初始化的 ForwardingNode"空节点
            //i是31
            else if ((f = tabAt(tab, i)) == null)
                advance = casTabAt(tab, i, null, fwd);
            //表示该位置已经完成了迁移，也就是如果线程A已经处理过这个节点，那么线程B处理这个节点
            //时，hash值一定为 MOVED
            else if ((fh = f.hash) == MOVED)
                advance = true; // already processed
            else {
                // !=MOVED,就走到了这里
                // 在上面 while (advance) {进行了--i，31-->30开始处理30节点
                synchronized (f) {//对数组该节点位置加锁，开始处理数组该位置的迁移工作
                    //处理链表
                    if (tabAt(tab, i) == f) {//再做一次校验
                        //一个高位节点,一个低位节点

                        /*玄机*/
                    //     /为什么这么做？
                    //     1. 一个一个节点迁移，需要计算很多次这个点hash值，有了高低位，只需要在这里进行一次计算，且迁移也是一次
                    //     2. 刚开始putval的时候(f = tabAt(tab, i = (n - 1) & hash)) == null，通过例子发现
                    // 对于同一个hash值，n=16和n=32这个式子计算出来是不一样的，而中间正好相差n，就是数组的长度，所以这里把高位链添加到i+n的地方，
                    //     下次获取的时候相当于一下就获取到了低位链和高伟链的数据

                        Node<K, V> ln, hn;//1n表示低位，hn表示高位；接下来这段代码的作用是把链表拆分成两部分，0在低位，1在高位
                        if (fh >= 0) {

                            //  高低位分开总结
                            // 当前节点fh&n   !=   尾结点fh&n  就用尾结点的
                            //所以就是尾结点的第x位等于0？ 0->低位  否则->高位

                            //fh往上面上面else if ((fh = f.hash) == MOVED)进行了赋值,就是当前节点的哈希值
                            //n tab长度
                            //把当前链表分成两类
                            //a类节点哈希值的第x位等于0(fh & n)
                            //b类节点哈希值的第x位不等于0(fh & n)
                            // 数组的长度一定是2的倍数,所以是0000000100000这中,所以只要hash值某一位0就是低位
                            int runBit = fh & n;
                            //lastRun最终要处理的节点
                            Node<K, V> lastRun = f;// 尾节点，且和头节点的 hash 值取于不相等
                            //遍历当前 bucket的链表，目的是尽量重用Node链表尾部的一部分
                            //从这里一直到下面set可以不用看,就是生成两个链,一个低位链,一个高位链
                            //下一个节点......下一个的下一个
                            //一条链上的hash值相等吗？  不相等 putval的时候 (n-1)&hash 只是为了找到位置。

                            //这个for循环找到lastRun，从lastRun开始后面的要在低位都在低位，要在高位都在高位
                            for (Node<K, V> p = f.next; p != null; p = p.next) {
                                // 取于桶中每个节点的 hash 值
                                //下一个节点的hash&n  当前节点的hash&n，因为一直循环所以就是尾结点和头结点的hash&n比较
                                int b = p.hash & n;
                                // 如果节点的 hash 值和首节点的 hash 值取于结果不同
                                if (b != runBit) {
                                    runBit = b;// 更新 runBit为尾结点的，用于下面判断 lastRun 该赋值给 ln 还是 hn。
                                    lastRun = p;// 这个 lastRun 保证后面的节点与自己的取于值相同，避免后面没有必要的循环，因为上面是从p开始循环的。
                                    // 所以这里到lastrun就不循环了
                                }
                            }
                            // 把lastRun当做高位链或者低位链的开始
                            //这里试试为了设置lastRun
                            if (runBit == 0) {//如果最后更新的 runBit，设置低位节点
                                ln = lastRun;
                                hn = null;
                            } else {//否则，设置高位节点
                                hn = lastRun;// 如果最后更新的 runBit 是 1， 设置高位节点
                                ln = null;
                            }
                            //构造高位以及低位的链表
                            // 再次循环，生成两个链表，lastRun 作为停止条件，这样就是避免无谓的循环（lastRun 后面都是相同的取于结果）

                            // 将原本的一个链表根据hash&n分为2个链表，构建新链表采用头插法
                            // 无法概括两个新链表相对旧链表的顺序，有很多可能，并不是一个正序，一个倒序

                            // 这个for循环，把lastRun前面装配到高位节点或者低位节点
                            for (Node<K, V> p = f; p != lastRun; p = p.next) {
                                int ph = p.hash;
                                K pk = p.key;
                                V pv = p.val;
                                // 如果与运算结果是 0，那么就还在低位
                                if ((ph & n) == 0)// 如果是0 ，那么创建低位节点
                                    ln = new Node<K, V>(ph, pk, pv, ln);
                                else // 1 则创建高位
                                    hn = new Node<K, V>(ph, pk, pv, hn);
                            }

                            setTabAt(nextTab, i, ln);//将低位的链表放在i位置也就是不动 低位链不需要变
                            setTabAt(nextTab, i + n, hn);//将高位链表放在i+n位置,n是数组的长度,是假如当前14 14+16=30
                            setTabAt(tab, i, fwd);//把旧 table的hash桶中放置转发节点，表明此hash桶已经被处理
                            advance = true;
                        }
                        //红黑树的扩容部分暂时忽略
                        else if (f instanceof TreeBin) {
                            TreeBin<K, V> t = (TreeBin<K, V>) f;
                            TreeNode<K, V> lo = null, loTail = null;
                            TreeNode<K, V> hi = null, hiTail = null;
                            int lc = 0, hc = 0;
                            for (Node<K, V> e = t.first; e != null; e = e.next) {
                                int h = e.hash;
                                TreeNode<K, V> p = new TreeNode<K, V>
                                        (h, e.key, e.val, null, null);
                                if ((h & n) == 0) {
                                    if ((p.prev = loTail) == null)
                                        lo = p;
                                    else
                                        loTail.next = p;
                                    loTail = p;
                                    ++lc;
                                } else {
                                    if ((p.prev = hiTail) == null)
                                        hi = p;
                                    else
                                        hiTail.next = p;
                                    hiTail = p;
                                    ++hc;
                                }
                            }
                            ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                                    (hc != 0) ? new TreeBin<K, V>(lo) : t;
                            hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                                    (lc != 0) ? new TreeBin<K, V>(hi) : t;
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                    }
                }
            }
        }
    }

    /* ---------------- Counter support -------------- */

    /**
     * A padded cell for distributing counts.  Adapted from LongAdder
     * and Striped64.  See their internal docs for explanation.
     */
    @sun.misc.Contended
    static final class CounterCell {
        volatile long value;

        CounterCell(long x) {
            value = x;
        }
    }
    //看到这段代码就能够明白了， CounterCell 数组的每个元素，都存储一个元素个数，而实际我们调用size 方法就是通过这个循环累加来得到的
    //又是一个设计精华，大家可以借鉴； 有了这个前提，再会过去看 addCount 这个方法，就容易理解一些了
    final long sumCount() {
        CounterCell[] as = counterCells;
        CounterCell a;
        long sum = baseCount;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    // See LongAdder version for explanation
    //fullAddCount 主要是用来初始化 CounterCell，来记录元素个数，里面包含扩容，初始化等
    // 操作
    private final void fullAddCount(long x, boolean wasUncontended) {
        int h;
        //获取当前线程的 probe的值，如果值为0，则初始化当前线程的 probe的值，probe就是随机数
        if ((h = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();      // force initialization
            h = ThreadLocalRandom.getProbe();
            wasUncontended = true;//由于重新生成了 probe，未冲突标志位设置为true
        }
        //这是一个局部变量用于表达是否与其他的线程发生了碰撞
        boolean collide = false;                // True if last slot nonempty
        for (; ; ) {//自旋
            CounterCell[] as;
            CounterCell a;
            int n;
            long v;
            if ((as = counterCells) != null && (n = as.length) > 0) {
                //说明 counterdiells已经被初始化过了，我们先跳过这个代码，先看初始化部分

                //先初始化CounterCell，value就是参数x即个数；cellsBusy相当于AQS中的同步状态，
                //即可以看成是一个锁，获取锁操作就是cas将其从0改为1；被锁保护的代码中，
                //将CounterCell对象放进(n - 1) & h位置，再将cellsBusy = 0；成功就结束循环
                //h是当前线程的随机数，看上面，n是CounterCell数组的大小，总的来说就是一个随机数（对应于当前线程）
                if ((a = as[(n - 1) & h]) == null) {//当前线程对应的计数器槽为空 //寻址位置为空 // 通过该值与当前线程 probe 求与，获得cells 的下标元素，和 hash 表获取索引是一样的
                    if (cellsBusy == 0) {  // 锁空闲  //cellsBusy=0 表示 counterCells 不在初始化或者扩容状态下         // Try to attach new Cell
                        CounterCell r = new CounterCell(x); //创建CounterCell，将x保存在其value字段中 // Optimistic create  //构造一个 CounterCell 的值，传入元素个数
                        if (cellsBusy == 0 &&// 获取锁 //通过 cas 设置 cellsBusy 标识，防止其他线程来对 counterCells 并发处理
                                U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                            boolean created = false; // 对象是否成功放入数组寻址位置
                            try {               // Recheck under lock
                                CounterCell[] rs;
                                int m, j;
                                //将初始化的r对象的元素个数放在对应下标的位置
                                //再次确认
                                if ((rs = counterCells) != null &&
                                        (m = rs.length) > 0 &&
                                        rs[j = (m - 1) & h] == null) {
                                    rs[j] = r; // 将创建的CounterCell对象放入寻址位置
                                    created = true; // 代表操作成功
                                }
                            } finally {//恢复标志位
                                cellsBusy = 0; //释放锁
                            }
                            if (created)//创建成功，退出循环  // 操作成功跳出循环
                                break;
                            continue;  //说明指定cells下标位置的数据不为空，则进行下一次循环         // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                //说明在 account方法中cas失败了，并且获取 probe的值不为空

                //在addCount中如果counterCells已初始化，之后会寻址得到CounterCell对象，CAS更新其value
                // 若失败会调用fullAddCount，wasUncontended参数为false，表明存在竞争，这里对于此的处理是再循环一次
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash//设置为未冲突标识，进入下一次自旋,再来一次
                //由于指定下标位置的cell值不为空，则直接通过cas进行原子累加，如果成功，则直接退出
                //        命中了同一个槽位
                 //到这说明寻址位置非空，则CAS更新其value计数，成功就跳出循环
                else if (U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))
                    break;
                //如果已经有其他线程建立了新的 counterCells或者 counterCells或者大于CPU核心数（很巧妙，线程的并发数不会超过cpu核心数）
                 // 上面更新失败到这里检查counterCells数组是否已经扩容，是否达到上限
                else if (counterCells != as || n >= NCPU)
                    //这里采取的措施就是让线程多循环几次，因为每次都会得到新的probe，这次不成功下次说不定就成功了
                    collide = false;        //设置当前线程的循环失败不进行扩容   // At max size or stale
                else if (!collide) //恢复collide状态，标识下次循环会进行扩容  //在最后手段扩容前再让线程循环一次
                    collide = true;//标识下次循环会进行扩容
                //进入这个步骤，说明 Countercell数组容量不够，线程竞争较大，所以先设置一个标识表示为正在扩容
                //上面的措施会让线程循环多次，若还不行说明数组太小竞争太激烈，需要扩容counterCells数组
                // 在对应的CounterCell上cas失败1或者2次(看wasUncontended和collide的值)后,尝试进行扩容
                //先获取锁
                else if (cellsBusy == 0 &&
                        U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                    try {
                        //扩容前的检查，因为数组可能已经被其它线程扩容了
                        if (counterCells == as) {// Expand table unless stale
                            //扩容一倍2变成4，这个扩容比较简单
                            CounterCell[] rs = new CounterCell[n << 1];// 2倍
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            counterCells = rs;
                        }
                    } finally {
                        cellsBusy = 0;//恢复标识  // 释放锁
                    }
                    collide = false;
                    continue;     //继续下一次旋     // 重新循环          // Retry with expanded table
                }
                // 每次循环都会重新计算probe值
                h = ThreadLocalRandom.advanceProbe(h); //更新随机数的值
            }

            //上面是counterCells数组已初始化的情况，下面是未初始化情况的处理
            //cellbusy=0表示没有在做初始化，通过cas更新cellsbusy的值标注当前线程正在做初始化操作
            else if (cellsBusy == 0 && counterCells == as &&
                    U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                boolean init = false;
                try {                           // Initialize table
                    if (counterCells == as) {
                        CounterCell[] rs = new CounterCell[2]; //初始化容量为2
                        //h是一个随机数，这样就像一个路由算法，就不会一直落在数组的同一个位置里面
                        rs[h & 1] = new CounterCell(x);//将x也就是元素的个数放在指定的数组下标位置
                        counterCells = rs;//赋值给 counterslls
                        init = true;//设置初始化完成标识
                    }
                } finally {
                    cellsBusy = 0;//恢复标识
                }
                if (init)
                    break;
            }
            //竞争激烈，其它线程占据ce11数组，直接累加在base变量中
            else if (U.compareAndSwapLong(this, BASECOUNT, v = baseCount, v + x))
                break;                          // Fall back on using base
        }
    }

    /* ---------------- Conversion from/to TreeBins -------------- */

    /**
     * Replaces all linked nodes in bin at given index unless table is
     * too small, in which case resizes instead.
     */
    private final void treeifyBin(Node<K, V>[] tab, int index) {
        Node<K, V> b;
        int n, sc;
        if (tab != null) {
            if ((n = tab.length) < MIN_TREEIFY_CAPACITY)//tab的长度是不是小于64，如果是，则执行扩容
                tryPresize(n << 1);
            else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {//否则，将当前链表转化为红黑树结构存储
                synchronized (b) {
                    if (tabAt(tab, index) == b) {//将链表转换成红黑树
                        TreeNode<K, V> hd = null, tl = null;
                        for (Node<K, V> e = b; e != null; e = e.next) {
                            TreeNode<K, V> p =
                                    new TreeNode<K, V>(e.hash, e.key, e.val,
                                            null, null);
                            if ((p.prev = tl) == null)
                                hd = p;
                            else
                                tl.next = p;
                            tl = p;
                        }
                        setTabAt(tab, index, new TreeBin<K, V>(hd));
                    }
                }
            }
        }
    }

    /**
     * Returns a list on non-TreeNodes replacing those in given list.
     */
    static <K, V> Node<K, V> untreeify(Node<K, V> b) {
        Node<K, V> hd = null, tl = null;
        for (Node<K, V> q = b; q != null; q = q.next) {
            Node<K, V> p = new Node<K, V>(q.hash, q.key, q.val, null);
            if (tl == null)
                hd = p;
            else
                tl.next = p;
            tl = p;
        }
        return hd;
    }

    /* ---------------- TreeNodes -------------- */

    /**
     * Nodes for use in TreeBins
     */
    static final class TreeNode<K, V> extends Node<K, V> {
        TreeNode<K, V> parent;  // red-black tree links
        TreeNode<K, V> left;
        TreeNode<K, V> right;
        TreeNode<K, V> prev;    // needed to unlink next upon deletion
        boolean red;

        TreeNode(int hash, K key, V val, Node<K, V> next,
                 TreeNode<K, V> parent) {
            super(hash, key, val, next);
            this.parent = parent;
        }

        Node<K, V> find(int h, Object k) {
            return findTreeNode(h, k, null);
        }

        /**
         * Returns the TreeNode (or null if not found) for the given key
         * starting at given root.
         */
        final TreeNode<K, V> findTreeNode(int h, Object k, Class<?> kc) {
            if (k != null) {
                TreeNode<K, V> p = this;
                do {
                    int ph, dir;
                    K pk;
                    TreeNode<K, V> q;
                    TreeNode<K, V> pl = p.left, pr = p.right;
                    if ((ph = p.hash) > h)
                        p = pl;
                    else if (ph < h)
                        p = pr;
                    else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                        return p;
                    else if (pl == null)
                        p = pr;
                    else if (pr == null)
                        p = pl;
                    else if ((kc != null ||
                            (kc = comparableClassFor(k)) != null) &&
                            (dir = compareComparables(kc, k, pk)) != 0)
                        p = (dir < 0) ? pl : pr;
                    else if ((q = pr.findTreeNode(h, k, kc)) != null)
                        return q;
                    else
                        p = pl;
                } while (p != null);
            }
            return null;
        }
    }

    /* ---------------- TreeBins -------------- */

    /**
     * TreeNodes used at the heads of bins. TreeBins do not hold user
     * keys or values, but instead point to list of TreeNodes and
     * their root. They also maintain a parasitic read-write lock
     * forcing writers (who hold bin lock) to wait for readers (who do
     * not) to complete before tree restructuring operations.
     */
    static final class TreeBin<K, V> extends Node<K, V> {
        TreeNode<K, V> root;
        volatile TreeNode<K, V> first;
        volatile Thread waiter;
        volatile int lockState;
        // values for lockState
        static final int WRITER = 1; // set while holding write lock
        static final int WAITER = 2; // set when waiting for write lock
        static final int READER = 4; // increment value for setting read lock

        /**
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
        static int tieBreakOrder(Object a, Object b) {
            int d;
            if (a == null || b == null ||
                    (d = a.getClass().getName().
                            compareTo(b.getClass().getName())) == 0)
                d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                        -1 : 1);
            return d;
        }

        /**
         * Creates bin with initial set of nodes headed by b.
         */
        TreeBin(TreeNode<K, V> b) {
            super(TREEBIN, null, null, null);
            this.first = b;
            TreeNode<K, V> r = null;
            for (TreeNode<K, V> x = b, next; x != null; x = next) {
                next = (TreeNode<K, V>) x.next;
                x.left = x.right = null;
                if (r == null) {
                    x.parent = null;
                    x.red = false;
                    r = x;
                } else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K, V> p = r; ; ) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1;
                        else if (ph < h)
                            dir = 1;
                        else if ((kc == null &&
                                (kc = comparableClassFor(k)) == null) ||
                                (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);
                        TreeNode<K, V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            r = balanceInsertion(r, x);
                            break;
                        }
                    }
                }
            }
            this.root = r;
            assert checkInvariants(root);
        }

        /**
         * Acquires write lock for tree restructuring.
         */
        private final void lockRoot() {
            if (!U.compareAndSwapInt(this, LOCKSTATE, 0, WRITER))
                contendedLock(); // offload to separate method
        }

        /**
         * Releases write lock for tree restructuring.
         */
        private final void unlockRoot() {
            lockState = 0;
        }

        /**
         * Possibly blocks awaiting root lock.
         */
        private final void contendedLock() {
            boolean waiting = false;
            for (int s; ; ) {
                if (((s = lockState) & ~WAITER) == 0) {
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, WRITER)) {
                        if (waiting)
                            waiter = null;
                        return;
                    }
                } else if ((s & WAITER) == 0) {
                    if (U.compareAndSwapInt(this, LOCKSTATE, s, s | WAITER)) {
                        waiting = true;
                        waiter = Thread.currentThread();
                    }
                } else if (waiting)
                    LockSupport.park(this);
            }
        }

        /**
         * Returns matching node or null if none. Tries to search
         * using tree comparisons from root, but continues linear
         * search when lock not available.
         */
        final Node<K, V> find(int h, Object k) {
            if (k != null) {
                for (Node<K, V> e = first; e != null; ) {
                    int s;
                    K ek;
                    if (((s = lockState) & (WAITER | WRITER)) != 0) {
                        if (e.hash == h &&
                                ((ek = e.key) == k || (ek != null && k.equals(ek))))
                            return e;
                        e = e.next;
                    } else if (U.compareAndSwapInt(this, LOCKSTATE, s,
                            s + READER)) {
                        TreeNode<K, V> r, p;
                        try {
                            p = ((r = root) == null ? null :
                                    r.findTreeNode(h, k, null));
                        } finally {
                            Thread w;
                            if (U.getAndAddInt(this, LOCKSTATE, -READER) ==
                                    (READER | WAITER) && (w = waiter) != null)
                                LockSupport.unpark(w);
                        }
                        return p;
                    }
                }
            }
            return null;
        }

        /**
         * Finds or adds a node.
         *
         * @return null if added
         */
        final TreeNode<K, V> putTreeVal(int h, K k, V v) {
            Class<?> kc = null;
            boolean searched = false;
            for (TreeNode<K, V> p = root; ; ) {
                int dir, ph;
                K pk;
                if (p == null) {
                    first = root = new TreeNode<K, V>(h, k, v, null, null);
                    break;
                } else if ((ph = p.hash) > h)
                    dir = -1;
                else if (ph < h)
                    dir = 1;
                else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                    return p;
                else if ((kc == null &&
                        (kc = comparableClassFor(k)) == null) ||
                        (dir = compareComparables(kc, k, pk)) == 0) {
                    if (!searched) {
                        TreeNode<K, V> q, ch;
                        searched = true;
                        if (((ch = p.left) != null &&
                                (q = ch.findTreeNode(h, k, kc)) != null) ||
                                ((ch = p.right) != null &&
                                        (q = ch.findTreeNode(h, k, kc)) != null))
                            return q;
                    }
                    dir = tieBreakOrder(k, pk);
                }

                TreeNode<K, V> xp = p;
                if ((p = (dir <= 0) ? p.left : p.right) == null) {
                    TreeNode<K, V> x, f = first;
                    first = x = new TreeNode<K, V>(h, k, v, f, xp);
                    if (f != null)
                        f.prev = x;
                    if (dir <= 0)
                        xp.left = x;
                    else
                        xp.right = x;
                    if (!xp.red)
                        x.red = true;
                    else {
                        lockRoot();
                        try {
                            root = balanceInsertion(root, x);
                        } finally {
                            unlockRoot();
                        }
                    }
                    break;
                }
            }
            assert checkInvariants(root);
            return null;
        }

        /**
         * Removes the given node, that must be present before this
         * call.  This is messier than typical red-black deletion code
         * because we cannot swap the contents of an interior node
         * with a leaf successor that is pinned by "next" pointers
         * that are accessible independently of lock. So instead we
         * swap the tree linkages.
         *
         * @return true if now too small, so should be untreeified
         */
        final boolean removeTreeNode(TreeNode<K, V> p) {
            TreeNode<K, V> next = (TreeNode<K, V>) p.next;
            TreeNode<K, V> pred = p.prev;  // unlink traversal pointers
            TreeNode<K, V> r, rl;
            if (pred == null)
                first = next;
            else
                pred.next = next;
            if (next != null)
                next.prev = pred;
            if (first == null) {
                root = null;
                return true;
            }
            if ((r = root) == null || r.right == null || // too small
                    (rl = r.left) == null || rl.left == null)
                return true;
            lockRoot();
            try {
                TreeNode<K, V> replacement;
                TreeNode<K, V> pl = p.left;
                TreeNode<K, V> pr = p.right;
                if (pl != null && pr != null) {
                    TreeNode<K, V> s = pr, sl;
                    while ((sl = s.left) != null) // find successor
                        s = sl;
                    boolean c = s.red;
                    s.red = p.red;
                    p.red = c; // swap colors
                    TreeNode<K, V> sr = s.right;
                    TreeNode<K, V> pp = p.parent;
                    if (s == pr) { // p was s's direct parent
                        p.parent = s;
                        s.right = p;
                    } else {
                        TreeNode<K, V> sp = s.parent;
                        if ((p.parent = sp) != null) {
                            if (s == sp.left)
                                sp.left = p;
                            else
                                sp.right = p;
                        }
                        if ((s.right = pr) != null)
                            pr.parent = s;
                    }
                    p.left = null;
                    if ((p.right = sr) != null)
                        sr.parent = p;
                    if ((s.left = pl) != null)
                        pl.parent = s;
                    if ((s.parent = pp) == null)
                        r = s;
                    else if (p == pp.left)
                        pp.left = s;
                    else
                        pp.right = s;
                    if (sr != null)
                        replacement = sr;
                    else
                        replacement = p;
                } else if (pl != null)
                    replacement = pl;
                else if (pr != null)
                    replacement = pr;
                else
                    replacement = p;
                if (replacement != p) {
                    TreeNode<K, V> pp = replacement.parent = p.parent;
                    if (pp == null)
                        r = replacement;
                    else if (p == pp.left)
                        pp.left = replacement;
                    else
                        pp.right = replacement;
                    p.left = p.right = p.parent = null;
                }

                root = (p.red) ? r : balanceDeletion(r, replacement);

                if (p == replacement) {  // detach pointers
                    TreeNode<K, V> pp;
                    if ((pp = p.parent) != null) {
                        if (p == pp.left)
                            pp.left = null;
                        else if (p == pp.right)
                            pp.right = null;
                        p.parent = null;
                    }
                }
            } finally {
                unlockRoot();
            }
            assert checkInvariants(root);
            return false;
        }

        /* ------------------------------------------------------------ */
        // Red-black tree methods, all adapted from CLR

        static <K, V> TreeNode<K, V> rotateLeft(TreeNode<K, V> root,
                                                TreeNode<K, V> p) {
            TreeNode<K, V> r, pp, rl;
            if (p != null && (r = p.right) != null) {
                if ((rl = p.right = r.left) != null)
                    rl.parent = p;
                if ((pp = r.parent = p.parent) == null)
                    (root = r).red = false;
                else if (pp.left == p)
                    pp.left = r;
                else
                    pp.right = r;
                r.left = p;
                p.parent = r;
            }
            return root;
        }

        static <K, V> TreeNode<K, V> rotateRight(TreeNode<K, V> root,
                                                 TreeNode<K, V> p) {
            TreeNode<K, V> l, pp, lr;
            if (p != null && (l = p.left) != null) {
                if ((lr = p.left = l.right) != null)
                    lr.parent = p;
                if ((pp = l.parent = p.parent) == null)
                    (root = l).red = false;
                else if (pp.right == p)
                    pp.right = l;
                else
                    pp.left = l;
                l.right = p;
                p.parent = l;
            }
            return root;
        }

        static <K, V> TreeNode<K, V> balanceInsertion(TreeNode<K, V> root,
                                                      TreeNode<K, V> x) {
            x.red = true;
            for (TreeNode<K, V> xp, xpp, xppl, xppr; ; ) {
                if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (!xp.red || (xpp = xp.parent) == null)
                    return root;
                if (xp == (xppl = xpp.left)) {
                    if ((xppr = xpp.right) != null && xppr.red) {
                        xppr.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.right) {
                            root = rotateLeft(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateRight(root, xpp);
                            }
                        }
                    }
                } else {
                    if (xppl != null && xppl.red) {
                        xppl.red = false;
                        xp.red = false;
                        xpp.red = true;
                        x = xpp;
                    } else {
                        if (x == xp.left) {
                            root = rotateRight(root, x = xp);
                            xpp = (xp = x.parent) == null ? null : xp.parent;
                        }
                        if (xp != null) {
                            xp.red = false;
                            if (xpp != null) {
                                xpp.red = true;
                                root = rotateLeft(root, xpp);
                            }
                        }
                    }
                }
            }
        }

        static <K, V> TreeNode<K, V> balanceDeletion(TreeNode<K, V> root,
                                                     TreeNode<K, V> x) {
            for (TreeNode<K, V> xp, xpl, xpr; ; ) {
                if (x == null || x == root)
                    return root;
                else if ((xp = x.parent) == null) {
                    x.red = false;
                    return x;
                } else if (x.red) {
                    x.red = false;
                    return root;
                } else if ((xpl = xp.left) == x) {
                    if ((xpr = xp.right) != null && xpr.red) {
                        xpr.red = false;
                        xp.red = true;
                        root = rotateLeft(root, xp);
                        xpr = (xp = x.parent) == null ? null : xp.right;
                    }
                    if (xpr == null)
                        x = xp;
                    else {
                        TreeNode<K, V> sl = xpr.left, sr = xpr.right;
                        if ((sr == null || !sr.red) &&
                                (sl == null || !sl.red)) {
                            xpr.red = true;
                            x = xp;
                        } else {
                            if (sr == null || !sr.red) {
                                if (sl != null)
                                    sl.red = false;
                                xpr.red = true;
                                root = rotateRight(root, xpr);
                                xpr = (xp = x.parent) == null ?
                                        null : xp.right;
                            }
                            if (xpr != null) {
                                xpr.red = (xp == null) ? false : xp.red;
                                if ((sr = xpr.right) != null)
                                    sr.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateLeft(root, xp);
                            }
                            x = root;
                        }
                    }
                } else { // symmetric
                    if (xpl != null && xpl.red) {
                        xpl.red = false;
                        xp.red = true;
                        root = rotateRight(root, xp);
                        xpl = (xp = x.parent) == null ? null : xp.left;
                    }
                    if (xpl == null)
                        x = xp;
                    else {
                        TreeNode<K, V> sl = xpl.left, sr = xpl.right;
                        if ((sl == null || !sl.red) &&
                                (sr == null || !sr.red)) {
                            xpl.red = true;
                            x = xp;
                        } else {
                            if (sl == null || !sl.red) {
                                if (sr != null)
                                    sr.red = false;
                                xpl.red = true;
                                root = rotateLeft(root, xpl);
                                xpl = (xp = x.parent) == null ?
                                        null : xp.left;
                            }
                            if (xpl != null) {
                                xpl.red = (xp == null) ? false : xp.red;
                                if ((sl = xpl.left) != null)
                                    sl.red = false;
                            }
                            if (xp != null) {
                                xp.red = false;
                                root = rotateRight(root, xp);
                            }
                            x = root;
                        }
                    }
                }
            }
        }

        /**
         * Recursive invariant check
         */
        static <K, V> boolean checkInvariants(TreeNode<K, V> t) {
            TreeNode<K, V> tp = t.parent, tl = t.left, tr = t.right,
                    tb = t.prev, tn = (TreeNode<K, V>) t.next;
            if (tb != null && tb.next != t)
                return false;
            if (tn != null && tn.prev != t)
                return false;
            if (tp != null && t != tp.left && t != tp.right)
                return false;
            if (tl != null && (tl.parent != t || tl.hash > t.hash))
                return false;
            if (tr != null && (tr.parent != t || tr.hash < t.hash))
                return false;
            if (t.red && tl != null && tl.red && tr != null && tr.red)
                return false;
            if (tl != null && !checkInvariants(tl))
                return false;
            if (tr != null && !checkInvariants(tr))
                return false;
            return true;
        }

        private static final sun.misc.Unsafe U;
        private static final long LOCKSTATE;

        static {
            try {
                U = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TreeBin.class;
                LOCKSTATE = U.objectFieldOffset
                        (k.getDeclaredField("lockState"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /* ----------------Table Traversal -------------- */

    /**
     * Records the table, its length, and current traversal index for a
     * traverser that must process a region of a forwarded table before
     * proceeding with current table.
     */
    static final class TableStack<K, V> {
        int length;
        int index;
        Node<K, V>[] tab;
        TableStack<K, V> next;
    }

    /**
     * Encapsulates traversal for methods such as containsValue; also
     * serves as a base class for other iterators and spliterators.
     * <p>
     * Method advance visits once each still-valid node that was
     * reachable upon iterator construction. It might miss some that
     * were added to a bin after the bin was visited, which is OK wrt
     * consistency guarantees. Maintaining this property in the face
     * of possible ongoing resizes requires a fair amount of
     * bookkeeping state that is difficult to optimize away amidst
     * volatile accesses.  Even so, traversal maintains reasonable
     * throughput.
     * <p>
     * Normally, iteration proceeds bin-by-bin traversing lists.
     * However, if the table has been resized, then all future steps
     * must traverse both the bin at the current index as well as at
     * (index + baseSize); and so on for further resizings. To
     * paranoically cope with potential sharing by users of iterators
     * across threads, iteration terminates if a bounds checks fails
     * for a table read.
     */
    static class Traverser<K, V> {
        Node<K, V>[] tab;        // current table; updated if resized
        Node<K, V> next;         // the next entry to use
        TableStack<K, V> stack, spare; // to save/restore on ForwardingNodes
        int index;              // index of bin to use next
        int baseIndex;          // current index of initial table
        int baseLimit;          // index bound for initial table
        final int baseSize;     // initial table size

        Traverser(Node<K, V>[] tab, int size, int index, int limit) {
            this.tab = tab;
            this.baseSize = size;
            this.baseIndex = this.index = index;
            this.baseLimit = limit;
            this.next = null;
        }

        /**
         * Advances if possible, returning next valid node, or null if none.
         */
        final Node<K, V> advance() {
            Node<K, V> e;
            if ((e = next) != null)
                e = e.next;
            for (; ; ) {
                Node<K, V>[] t;
                int i, n;  // must use locals in checks
                if (e != null)
                    return next = e;
                if (baseIndex >= baseLimit || (t = tab) == null ||
                        (n = t.length) <= (i = index) || i < 0)
                    return next = null;
                if ((e = tabAt(t, i)) != null && e.hash < 0) {
                    if (e instanceof ForwardingNode) {
                        tab = ((ForwardingNode<K, V>) e).nextTable;
                        e = null;
                        pushState(t, i, n);
                        continue;
                    } else if (e instanceof TreeBin)
                        e = ((TreeBin<K, V>) e).first;
                    else
                        e = null;
                }
                if (stack != null)
                    recoverState(n);
                else if ((index = i + baseSize) >= n)
                    index = ++baseIndex; // visit upper slots if present
            }
        }

        /**
         * Saves traversal state upon encountering a forwarding node.
         */
        private void pushState(Node<K, V>[] t, int i, int n) {
            TableStack<K, V> s = spare;  // reuse if possible
            if (s != null)
                spare = s.next;
            else
                s = new TableStack<K, V>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        /**
         * Possibly pops traversal state.
         *
         * @param n length of current table
         */
        private void recoverState(int n) {
            TableStack<K, V> s;
            int len;
            while ((s = stack) != null && (index += (len = s.length)) >= n) {
                n = len;
                index = s.index;
                tab = s.tab;
                s.tab = null;
                TableStack<K, V> next = s.next;
                s.next = spare; // save for reuse
                stack = next;
                spare = s;
            }
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }

    /**
     * Base of key, value, and entry Iterators. Adds fields to
     * Traverser to support iterator.remove.
     */
    static class BaseIterator<K, V> extends Traverser<K, V> {
        final ConcurrentHashMap<K, V> map;
        Node<K, V> lastReturned;

        BaseIterator(Node<K, V>[] tab, int size, int index, int limit,
                     ConcurrentHashMap<K, V> map) {
            super(tab, size, index, limit);
            this.map = map;
            advance();
        }

        public final boolean hasNext() {
            return next != null;
        }

        public final boolean hasMoreElements() {
            return next != null;
        }

        public final void remove() {
            Node<K, V> p;
            if ((p = lastReturned) == null)
                throw new IllegalStateException();
            lastReturned = null;
            map.replaceNode(p.key, null, null);
        }
    }

    static final class KeyIterator<K, V> extends BaseIterator<K, V>
            implements Iterator<K>, Enumeration<K> {
        KeyIterator(Node<K, V>[] tab, int index, int size, int limit,
                    ConcurrentHashMap<K, V> map) {
            super(tab, index, size, limit, map);
        }

        public final K next() {
            Node<K, V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            K k = p.key;
            lastReturned = p;
            advance();
            return k;
        }

        public final K nextElement() {
            return next();
        }
    }

    static final class ValueIterator<K, V> extends BaseIterator<K, V>
            implements Iterator<V>, Enumeration<V> {
        ValueIterator(Node<K, V>[] tab, int index, int size, int limit,
                      ConcurrentHashMap<K, V> map) {
            super(tab, index, size, limit, map);
        }

        public final V next() {
            Node<K, V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            V v = p.val;
            lastReturned = p;
            advance();
            return v;
        }

        public final V nextElement() {
            return next();
        }
    }

    static final class EntryIterator<K, V> extends BaseIterator<K, V>
            implements Iterator<Map.Entry<K, V>> {
        EntryIterator(Node<K, V>[] tab, int index, int size, int limit,
                      ConcurrentHashMap<K, V> map) {
            super(tab, index, size, limit, map);
        }

        public final Map.Entry<K, V> next() {
            Node<K, V> p;
            if ((p = next) == null)
                throw new NoSuchElementException();
            K k = p.key;
            V v = p.val;
            lastReturned = p;
            advance();
            return new MapEntry<K, V>(k, v, map);
        }
    }

    /**
     * Exported Entry for EntryIterator
     */
    static final class MapEntry<K, V> implements Map.Entry<K, V> {
        final K key; // non-null
        V val;       // non-null
        final ConcurrentHashMap<K, V> map;

        MapEntry(K key, V val, ConcurrentHashMap<K, V> map) {
            this.key = key;
            this.val = val;
            this.map = map;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return val;
        }

        public int hashCode() {
            return key.hashCode() ^ val.hashCode();
        }

        public String toString() {
            return key + "=" + val;
        }

        public boolean equals(Object o) {
            Object k, v;
            Map.Entry<?, ?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?, ?>) o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    (k == key || k.equals(key)) &&
                    (v == val || v.equals(val)));
        }

        /**
         * Sets our entry's value and writes through to the map. The
         * value to return is somewhat arbitrary here. Since we do not
         * necessarily track asynchronous changes, the most recent
         * "previous" value could be different from what we return (or
         * could even have been removed, in which case the put will
         * re-establish). We do not and cannot guarantee more.
         */
        public V setValue(V value) {
            if (value == null) throw new NullPointerException();
            V v = val;
            val = value;
            map.put(key, value);
            return v;
        }
    }

    static final class KeySpliterator<K, V> extends Traverser<K, V>
            implements Spliterator<K> {
        long est;               // size estimate

        KeySpliterator(Node<K, V>[] tab, int size, int index, int limit,
                       long est) {
            super(tab, size, index, limit);
            this.est = est;
        }

        public Spliterator<K> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                    new KeySpliterator<K, V>(tab, baseSize, baseLimit = h,
                            f, est >>>= 1);
        }

        public void forEachRemaining(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K, V> p; (p = advance()) != null; )
                action.accept(p.key);
        }

        public boolean tryAdvance(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Node<K, V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(p.key);
            return true;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT |
                    Spliterator.NONNULL;
        }
    }

    static final class ValueSpliterator<K, V> extends Traverser<K, V>
            implements Spliterator<V> {
        long est;               // size estimate

        ValueSpliterator(Node<K, V>[] tab, int size, int index, int limit,
                         long est) {
            super(tab, size, index, limit);
            this.est = est;
        }

        public Spliterator<V> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                    new ValueSpliterator<K, V>(tab, baseSize, baseLimit = h,
                            f, est >>>= 1);
        }

        public void forEachRemaining(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K, V> p; (p = advance()) != null; )
                action.accept(p.val);
        }

        public boolean tryAdvance(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            Node<K, V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(p.val);
            return true;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return Spliterator.CONCURRENT | Spliterator.NONNULL;
        }
    }

    static final class EntrySpliterator<K, V> extends Traverser<K, V>
            implements Spliterator<Map.Entry<K, V>> {
        final ConcurrentHashMap<K, V> map; // To export MapEntry
        long est;               // size estimate

        EntrySpliterator(Node<K, V>[] tab, int size, int index, int limit,
                         long est, ConcurrentHashMap<K, V> map) {
            super(tab, size, index, limit);
            this.map = map;
            this.est = est;
        }

        public Spliterator<Map.Entry<K, V>> trySplit() {
            int i, f, h;
            return (h = ((i = baseIndex) + (f = baseLimit)) >>> 1) <= i ? null :
                    new EntrySpliterator<K, V>(tab, baseSize, baseLimit = h,
                            f, est >>>= 1, map);
        }

        public void forEachRemaining(Consumer<? super Map.Entry<K, V>> action) {
            if (action == null) throw new NullPointerException();
            for (Node<K, V> p; (p = advance()) != null; )
                action.accept(new MapEntry<K, V>(p.key, p.val, map));
        }

        public boolean tryAdvance(Consumer<? super Map.Entry<K, V>> action) {
            if (action == null) throw new NullPointerException();
            Node<K, V> p;
            if ((p = advance()) == null)
                return false;
            action.accept(new MapEntry<K, V>(p.key, p.val, map));
            return true;
        }

        public long estimateSize() {
            return est;
        }

        public int characteristics() {
            return Spliterator.DISTINCT | Spliterator.CONCURRENT |
                    Spliterator.NONNULL;
        }
    }

    // Parallel bulk operations

    /**
     * Computes initial batch value for bulk tasks. The returned value
     * is approximately exp2 of the number of times (minus one) to
     * split task by two before executing leaf action. This value is
     * faster to compute and more convenient to use as a guide to
     * splitting than is the depth, since it is used while dividing by
     * two anyway.
     */
    final int batchFor(long b) {
        long n;
        if (b == Long.MAX_VALUE || (n = sumCount()) <= 1L || n < b)
            return 0;
        int sp = ForkJoinPool.getCommonPoolParallelism() << 2; // slack of 4
        return (b <= 0L || (n /= b) >= sp) ? sp : (int) n;
    }

    /**
     * Performs the given action for each (key, value).
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param action               the action
     * @since 1.8
     */
    public void forEach(long parallelismThreshold,
                        BiConsumer<? super K, ? super V> action) {
        if (action == null) throw new NullPointerException();
        new ForEachMappingTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        action).invoke();
    }

    /**
     * Performs the given action for each non-null transformation
     * of each (key, value).
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case the action is not applied)
     * @param action               the action
     * @param <U>                  the return type of the transformer
     * @since 1.8
     */
    public <U> void forEach(long parallelismThreshold,
                            BiFunction<? super K, ? super V, ? extends U> transformer,
                            Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedMappingTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        transformer, action).invoke();
    }

    /**
     * Returns a non-null result from applying the given search
     * function on each (key, value), or null if none.  Upon
     * success, further element processing is suppressed and the
     * results of any other parallel invocations of the search
     * function are ignored.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param searchFunction       a function returning a non-null
     *                             result on success, else null
     * @param <U>                  the return type of the search function
     * @return a non-null result from applying the given search
     * function on each (key, value), or null if none
     * @since 1.8
     */
    public <U> U search(long parallelismThreshold,
                        BiFunction<? super K, ? super V, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchMappingsTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all (key, value) pairs using the given reducer to
     * combine values, or null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case it is not combined)
     * @param reducer              a commutative associative combining function
     * @param <U>                  the return type of the transformer
     * @return the result of accumulating the given transformation
     * of all (key, value) pairs
     * @since 1.8
     */
    public <U> U reduce(long parallelismThreshold,
                        BiFunction<? super K, ? super V, ? extends U> transformer,
                        BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all (key, value) pairs using the given reducer to
     * combine values, and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all (key, value) pairs
     * @since 1.8
     */
    public double reduceToDouble(long parallelismThreshold,
                                 ToDoubleBiFunction<? super K, ? super V> transformer,
                                 double basis,
                                 DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToDoubleTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all (key, value) pairs using the given reducer to
     * combine values, and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all (key, value) pairs
     * @since 1.8
     */
    public long reduceToLong(long parallelismThreshold,
                             ToLongBiFunction<? super K, ? super V> transformer,
                             long basis,
                             LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToLongTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all (key, value) pairs using the given reducer to
     * combine values, and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all (key, value) pairs
     * @since 1.8
     */
    public int reduceToInt(long parallelismThreshold,
                           ToIntBiFunction<? super K, ? super V> transformer,
                           int basis,
                           IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceMappingsToIntTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Performs the given action for each key.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param action               the action
     * @since 1.8
     */
    public void forEachKey(long parallelismThreshold,
                           Consumer<? super K> action) {
        if (action == null) throw new NullPointerException();
        new ForEachKeyTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        action).invoke();
    }

    /**
     * Performs the given action for each non-null transformation
     * of each key.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case the action is not applied)
     * @param action               the action
     * @param <U>                  the return type of the transformer
     * @since 1.8
     */
    public <U> void forEachKey(long parallelismThreshold,
                               Function<? super K, ? extends U> transformer,
                               Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedKeyTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        transformer, action).invoke();
    }

    /**
     * Returns a non-null result from applying the given search
     * function on each key, or null if none. Upon success,
     * further element processing is suppressed and the results of
     * any other parallel invocations of the search function are
     * ignored.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param searchFunction       a function returning a non-null
     *                             result on success, else null
     * @param <U>                  the return type of the search function
     * @return a non-null result from applying the given search
     * function on each key, or null if none
     * @since 1.8
     */
    public <U> U searchKeys(long parallelismThreshold,
                            Function<? super K, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchKeysTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * Returns the result of accumulating all keys using the given
     * reducer to combine values, or null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating all keys using the given
     * reducer to combine values, or null if none
     * @since 1.8
     */
    public K reduceKeys(long parallelismThreshold,
                        BiFunction<? super K, ? super K, ? extends K> reducer) {
        if (reducer == null) throw new NullPointerException();
        return new ReduceKeysTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all keys using the given reducer to combine values, or
     * null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case it is not combined)
     * @param reducer              a commutative associative combining function
     * @param <U>                  the return type of the transformer
     * @return the result of accumulating the given transformation
     * of all keys
     * @since 1.8
     */
    public <U> U reduceKeys(long parallelismThreshold,
                            Function<? super K, ? extends U> transformer,
                            BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all keys using the given reducer to combine values, and
     * the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all keys
     * @since 1.8
     */
    public double reduceKeysToDouble(long parallelismThreshold,
                                     ToDoubleFunction<? super K> transformer,
                                     double basis,
                                     DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToDoubleTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all keys using the given reducer to combine values, and
     * the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all keys
     * @since 1.8
     */
    public long reduceKeysToLong(long parallelismThreshold,
                                 ToLongFunction<? super K> transformer,
                                 long basis,
                                 LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToLongTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all keys using the given reducer to combine values, and
     * the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all keys
     * @since 1.8
     */
    public int reduceKeysToInt(long parallelismThreshold,
                               ToIntFunction<? super K> transformer,
                               int basis,
                               IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceKeysToIntTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Performs the given action for each value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param action               the action
     * @since 1.8
     */
    public void forEachValue(long parallelismThreshold,
                             Consumer<? super V> action) {
        if (action == null)
            throw new NullPointerException();
        new ForEachValueTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        action).invoke();
    }

    /**
     * Performs the given action for each non-null transformation
     * of each value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case the action is not applied)
     * @param action               the action
     * @param <U>                  the return type of the transformer
     * @since 1.8
     */
    public <U> void forEachValue(long parallelismThreshold,
                                 Function<? super V, ? extends U> transformer,
                                 Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedValueTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        transformer, action).invoke();
    }

    /**
     * Returns a non-null result from applying the given search
     * function on each value, or null if none.  Upon success,
     * further element processing is suppressed and the results of
     * any other parallel invocations of the search function are
     * ignored.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param searchFunction       a function returning a non-null
     *                             result on success, else null
     * @param <U>                  the return type of the search function
     * @return a non-null result from applying the given search
     * function on each value, or null if none
     * @since 1.8
     */
    public <U> U searchValues(long parallelismThreshold,
                              Function<? super V, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchValuesTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * Returns the result of accumulating all values using the
     * given reducer to combine values, or null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating all values
     * @since 1.8
     */
    public V reduceValues(long parallelismThreshold,
                          BiFunction<? super V, ? super V, ? extends V> reducer) {
        if (reducer == null) throw new NullPointerException();
        return new ReduceValuesTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all values using the given reducer to combine values, or
     * null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case it is not combined)
     * @param reducer              a commutative associative combining function
     * @param <U>                  the return type of the transformer
     * @return the result of accumulating the given transformation
     * of all values
     * @since 1.8
     */
    public <U> U reduceValues(long parallelismThreshold,
                              Function<? super V, ? extends U> transformer,
                              BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all values using the given reducer to combine values,
     * and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all values
     * @since 1.8
     */
    public double reduceValuesToDouble(long parallelismThreshold,
                                       ToDoubleFunction<? super V> transformer,
                                       double basis,
                                       DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToDoubleTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all values using the given reducer to combine values,
     * and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all values
     * @since 1.8
     */
    public long reduceValuesToLong(long parallelismThreshold,
                                   ToLongFunction<? super V> transformer,
                                   long basis,
                                   LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToLongTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all values using the given reducer to combine values,
     * and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all values
     * @since 1.8
     */
    public int reduceValuesToInt(long parallelismThreshold,
                                 ToIntFunction<? super V> transformer,
                                 int basis,
                                 IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceValuesToIntTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Performs the given action for each entry.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param action               the action
     * @since 1.8
     */
    public void forEachEntry(long parallelismThreshold,
                             Consumer<? super Map.Entry<K, V>> action) {
        if (action == null) throw new NullPointerException();
        new ForEachEntryTask<K, V>(null, batchFor(parallelismThreshold), 0, 0, table,
                action).invoke();
    }

    /**
     * Performs the given action for each non-null transformation
     * of each entry.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case the action is not applied)
     * @param action               the action
     * @param <U>                  the return type of the transformer
     * @since 1.8
     */
    public <U> void forEachEntry(long parallelismThreshold,
                                 Function<Map.Entry<K, V>, ? extends U> transformer,
                                 Consumer<? super U> action) {
        if (transformer == null || action == null)
            throw new NullPointerException();
        new ForEachTransformedEntryTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        transformer, action).invoke();
    }

    /**
     * Returns a non-null result from applying the given search
     * function on each entry, or null if none.  Upon success,
     * further element processing is suppressed and the results of
     * any other parallel invocations of the search function are
     * ignored.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param searchFunction       a function returning a non-null
     *                             result on success, else null
     * @param <U>                  the return type of the search function
     * @return a non-null result from applying the given search
     * function on each entry, or null if none
     * @since 1.8
     */
    public <U> U searchEntries(long parallelismThreshold,
                               Function<Map.Entry<K, V>, ? extends U> searchFunction) {
        if (searchFunction == null) throw new NullPointerException();
        return new SearchEntriesTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        searchFunction, new AtomicReference<U>()).invoke();
    }

    /**
     * Returns the result of accumulating all entries using the
     * given reducer to combine values, or null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating all entries
     * @since 1.8
     */
    public Map.Entry<K, V> reduceEntries(long parallelismThreshold,
                                         BiFunction<Map.Entry<K, V>, Map.Entry<K, V>, ? extends Map.Entry<K, V>> reducer) {
        if (reducer == null) throw new NullPointerException();
        return new ReduceEntriesTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all entries using the given reducer to combine values,
     * or null if none.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element, or null if there is no transformation (in
     *                             which case it is not combined)
     * @param reducer              a commutative associative combining function
     * @param <U>                  the return type of the transformer
     * @return the result of accumulating the given transformation
     * of all entries
     * @since 1.8
     */
    public <U> U reduceEntries(long parallelismThreshold,
                               Function<Map.Entry<K, V>, ? extends U> transformer,
                               BiFunction<? super U, ? super U, ? extends U> reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesTask<K, V, U>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all entries using the given reducer to combine values,
     * and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all entries
     * @since 1.8
     */
    public double reduceEntriesToDouble(long parallelismThreshold,
                                        ToDoubleFunction<Map.Entry<K, V>> transformer,
                                        double basis,
                                        DoubleBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToDoubleTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all entries using the given reducer to combine values,
     * and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all entries
     * @since 1.8
     */
    public long reduceEntriesToLong(long parallelismThreshold,
                                    ToLongFunction<Map.Entry<K, V>> transformer,
                                    long basis,
                                    LongBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToLongTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }

    /**
     * Returns the result of accumulating the given transformation
     * of all entries using the given reducer to combine values,
     * and the given basis as an identity value.
     *
     * @param parallelismThreshold the (estimated) number of elements
     *                             needed for this operation to be executed in parallel
     * @param transformer          a function returning the transformation
     *                             for an element
     * @param basis                the identity (initial default value) for the reduction
     * @param reducer              a commutative associative combining function
     * @return the result of accumulating the given transformation
     * of all entries
     * @since 1.8
     */
    public int reduceEntriesToInt(long parallelismThreshold,
                                  ToIntFunction<Map.Entry<K, V>> transformer,
                                  int basis,
                                  IntBinaryOperator reducer) {
        if (transformer == null || reducer == null)
            throw new NullPointerException();
        return new MapReduceEntriesToIntTask<K, V>
                (null, batchFor(parallelismThreshold), 0, 0, table,
                        null, transformer, basis, reducer).invoke();
    }


    /* ----------------Views -------------- */

    /**
     * Base class for views.
     */
    abstract static class CollectionView<K, V, E>
            implements Collection<E>, java.io.Serializable {
        private static final long serialVersionUID = 7249069246763182397L;
        final ConcurrentHashMap<K, V> map;

        CollectionView(ConcurrentHashMap<K, V> map) {
            this.map = map;
        }

        /**
         * Returns the map backing this view.
         *
         * @return the map backing this view
         */
        public ConcurrentHashMap<K, V> getMap() {
            return map;
        }

        /**
         * Removes all of the elements from this view, by removing all
         * the mappings from the map backing this view.
         */
        public final void clear() {
            map.clear();
        }

        public final int size() {
            return map.size();
        }

        public final boolean isEmpty() {
            return map.isEmpty();
        }

        // implementations below rely on concrete classes supplying these
        // abstract methods

        /**
         * Returns an iterator over the elements in this collection.
         * <p>
         * <p>The returned iterator is
         * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
         *
         * @return an iterator over the elements in this collection
         */
        public abstract Iterator<E> iterator();

        public abstract boolean contains(Object o);

        public abstract boolean remove(Object o);

        private static final String oomeMsg = "Required array size too large";

        public final Object[] toArray() {
            long sz = map.mappingCount();
            if (sz > MAX_ARRAY_SIZE)
                throw new OutOfMemoryError(oomeMsg);
            int n = (int) sz;
            Object[] r = new Object[n];
            int i = 0;
            for (E e : this) {
                if (i == n) {
                    if (n >= MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError(oomeMsg);
                    if (n >= MAX_ARRAY_SIZE - (MAX_ARRAY_SIZE >>> 1) - 1)
                        n = MAX_ARRAY_SIZE;
                    else
                        n += (n >>> 1) + 1;
                    r = Arrays.copyOf(r, n);
                }
                r[i++] = e;
            }
            return (i == n) ? r : Arrays.copyOf(r, i);
        }

        @SuppressWarnings("unchecked")
        public final <T> T[] toArray(T[] a) {
            long sz = map.mappingCount();
            if (sz > MAX_ARRAY_SIZE)
                throw new OutOfMemoryError(oomeMsg);
            int m = (int) sz;
            T[] r = (a.length >= m) ? a :
                    (T[]) java.lang.reflect.Array
                            .newInstance(a.getClass().getComponentType(), m);
            int n = r.length;
            int i = 0;
            for (E e : this) {
                if (i == n) {
                    if (n >= MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError(oomeMsg);
                    if (n >= MAX_ARRAY_SIZE - (MAX_ARRAY_SIZE >>> 1) - 1)
                        n = MAX_ARRAY_SIZE;
                    else
                        n += (n >>> 1) + 1;
                    r = Arrays.copyOf(r, n);
                }
                r[i++] = (T) e;
            }
            if (a == r && i < n) {
                r[i] = null; // null-terminate
                return r;
            }
            return (i == n) ? r : Arrays.copyOf(r, i);
        }

        /**
         * Returns a string representation of this collection.
         * The string representation consists of the string representations
         * of the collection's elements in the order they are returned by
         * its iterator, enclosed in square brackets ({@code "[]"}).
         * Adjacent elements are separated by the characters {@code ", "}
         * (comma and space).  Elements are converted to strings as by
         * {@link String#valueOf(Object)}.
         *
         * @return a string representation of this collection
         */
        public final String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            Iterator<E> it = iterator();
            if (it.hasNext()) {
                for (; ; ) {
                    Object e = it.next();
                    sb.append(e == this ? "(this Collection)" : e);
                    if (!it.hasNext())
                        break;
                    sb.append(',').append(' ');
                }
            }
            return sb.append(']').toString();
        }

        public final boolean containsAll(Collection<?> c) {
            if (c != this) {
                for (Object e : c) {
                    if (e == null || !contains(e))
                        return false;
                }
            }
            return true;
        }

        public final boolean removeAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            for (Iterator<E> it = iterator(); it.hasNext(); ) {
                if (c.contains(it.next())) {
                    it.remove();
                    modified = true;
                }
            }
            return modified;
        }

        public final boolean retainAll(Collection<?> c) {
            if (c == null) throw new NullPointerException();
            boolean modified = false;
            for (Iterator<E> it = iterator(); it.hasNext(); ) {
                if (!c.contains(it.next())) {
                    it.remove();
                    modified = true;
                }
            }
            return modified;
        }

    }

    /**
     * A view of a ConcurrentHashMap as a {@link Set} of keys, in
     * which additions may optionally be enabled by mapping to a
     * common value.  This class cannot be directly instantiated.
     * See {@link #keySet() keySet()},
     * {@link #keySet(Object) keySet(V)},
     * {@link #newKeySet() newKeySet()},
     * {@link #newKeySet(int) newKeySet(int)}.
     *
     * @since 1.8
     */
    public static class KeySetView<K, V> extends CollectionView<K, V, K>
            implements Set<K>, java.io.Serializable {
        private static final long serialVersionUID = 7249069246763182397L;
        private final V value;

        KeySetView(ConcurrentHashMap<K, V> map, V value) {  // non-public
            super(map);
            this.value = value;
        }

        /**
         * Returns the default mapped value for additions,
         * or {@code null} if additions are not supported.
         *
         * @return the default mapped value for additions, or {@code null}
         * if not supported
         */
        public V getMappedValue() {
            return value;
        }

        /**
         * {@inheritDoc}
         *
         * @throws NullPointerException if the specified key is null
         */
        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        /**
         * Removes the key from this map view, by removing the key (and its
         * corresponding value) from the backing map.  This method does
         * nothing if the key is not in the map.
         *
         * @param o the key to be removed from the backing map
         * @return {@code true} if the backing map contained the specified key
         * @throws NullPointerException if the specified key is null
         */
        public boolean remove(Object o) {
            return map.remove(o) != null;
        }

        /**
         * @return an iterator over the keys of the backing map
         */
        public Iterator<K> iterator() {
            Node<K, V>[] t;
            ConcurrentHashMap<K, V> m = map;
            int f = (t = m.table) == null ? 0 : t.length;
            return new KeyIterator<K, V>(t, f, 0, f, m);
        }

        /**
         * Adds the specified key to this set view by mapping the key to
         * the default mapped value in the backing map, if defined.
         *
         * @param e key to be added
         * @return {@code true} if this set changed as a result of the call
         * @throws NullPointerException          if the specified key is null
         * @throws UnsupportedOperationException if no default mapped value
         *                                       for additions was provided
         */
        public boolean add(K e) {
            V v;
            if ((v = value) == null)
                throw new UnsupportedOperationException();
            return map.putVal(e, v, true) == null;
        }

        /**
         * Adds all of the elements in the specified collection to this set,
         * as if by calling {@link #add} on each one.
         *
         * @param c the elements to be inserted into this set
         * @return {@code true} if this set changed as a result of the call
         * @throws NullPointerException          if the collection or any of its
         *                                       elements are {@code null}
         * @throws UnsupportedOperationException if no default mapped value
         *                                       for additions was provided
         */
        public boolean addAll(Collection<? extends K> c) {
            boolean added = false;
            V v;
            if ((v = value) == null)
                throw new UnsupportedOperationException();
            for (K e : c) {
                if (map.putVal(e, v, true) == null)
                    added = true;
            }
            return added;
        }

        public int hashCode() {
            int h = 0;
            for (K e : this)
                h += e.hashCode();
            return h;
        }

        public boolean equals(Object o) {
            Set<?> c;
            return ((o instanceof Set) &&
                    ((c = (Set<?>) o) == this ||
                            (containsAll(c) && c.containsAll(this))));
        }

        public Spliterator<K> spliterator() {
            Node<K, V>[] t;
            ConcurrentHashMap<K, V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new KeySpliterator<K, V>(t, f, 0, f, n < 0L ? 0L : n);
        }

        public void forEach(Consumer<? super K> action) {
            if (action == null) throw new NullPointerException();
            Node<K, V>[] t;
            if ((t = map.table) != null) {
                Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
                for (Node<K, V> p; (p = it.advance()) != null; )
                    action.accept(p.key);
            }
        }
    }

    /**
     * A view of a ConcurrentHashMap as a {@link Collection} of
     * values, in which additions are disabled. This class cannot be
     * directly instantiated. See {@link #values()}.
     */
    static final class ValuesView<K, V> extends CollectionView<K, V, V>
            implements Collection<V>, java.io.Serializable {
        private static final long serialVersionUID = 2249069246763182397L;

        ValuesView(ConcurrentHashMap<K, V> map) {
            super(map);
        }

        public final boolean contains(Object o) {
            return map.containsValue(o);
        }

        public final boolean remove(Object o) {
            if (o != null) {
                for (Iterator<V> it = iterator(); it.hasNext(); ) {
                    if (o.equals(it.next())) {
                        it.remove();
                        return true;
                    }
                }
            }
            return false;
        }

        public final Iterator<V> iterator() {
            ConcurrentHashMap<K, V> m = map;
            Node<K, V>[] t;
            int f = (t = m.table) == null ? 0 : t.length;
            return new ValueIterator<K, V>(t, f, 0, f, m);
        }

        public final boolean add(V e) {
            throw new UnsupportedOperationException();
        }

        public final boolean addAll(Collection<? extends V> c) {
            throw new UnsupportedOperationException();
        }

        public Spliterator<V> spliterator() {
            Node<K, V>[] t;
            ConcurrentHashMap<K, V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new ValueSpliterator<K, V>(t, f, 0, f, n < 0L ? 0L : n);
        }

        public void forEach(Consumer<? super V> action) {
            if (action == null) throw new NullPointerException();
            Node<K, V>[] t;
            if ((t = map.table) != null) {
                Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
                for (Node<K, V> p; (p = it.advance()) != null; )
                    action.accept(p.val);
            }
        }
    }

    /**
     * A view of a ConcurrentHashMap as a {@link Set} of (key, value)
     * entries.  This class cannot be directly instantiated. See
     * {@link #entrySet()}.
     */
    static final class EntrySetView<K, V> extends CollectionView<K, V, Map.Entry<K, V>>
            implements Set<Map.Entry<K, V>>, java.io.Serializable {
        private static final long serialVersionUID = 2249069246763182397L;

        EntrySetView(ConcurrentHashMap<K, V> map) {
            super(map);
        }

        public boolean contains(Object o) {
            Object k, v, r;
            Map.Entry<?, ?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?, ?>) o).getKey()) != null &&
                    (r = map.get(k)) != null &&
                    (v = e.getValue()) != null &&
                    (v == r || v.equals(r)));
        }

        public boolean remove(Object o) {
            Object k, v;
            Map.Entry<?, ?> e;
            return ((o instanceof Map.Entry) &&
                    (k = (e = (Map.Entry<?, ?>) o).getKey()) != null &&
                    (v = e.getValue()) != null &&
                    map.remove(k, v));
        }

        /**
         * @return an iterator over the entries of the backing map
         */
        public Iterator<Map.Entry<K, V>> iterator() {
            ConcurrentHashMap<K, V> m = map;
            Node<K, V>[] t;
            int f = (t = m.table) == null ? 0 : t.length;
            return new EntryIterator<K, V>(t, f, 0, f, m);
        }

        public boolean add(Entry<K, V> e) {
            return map.putVal(e.getKey(), e.getValue(), false) == null;
        }

        public boolean addAll(Collection<? extends Entry<K, V>> c) {
            boolean added = false;
            for (Entry<K, V> e : c) {
                if (add(e))
                    added = true;
            }
            return added;
        }

        public final int hashCode() {
            int h = 0;
            Node<K, V>[] t;
            if ((t = map.table) != null) {
                Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
                for (Node<K, V> p; (p = it.advance()) != null; ) {
                    h += p.hashCode();
                }
            }
            return h;
        }

        public final boolean equals(Object o) {
            Set<?> c;
            return ((o instanceof Set) &&
                    ((c = (Set<?>) o) == this ||
                            (containsAll(c) && c.containsAll(this))));
        }

        public Spliterator<Map.Entry<K, V>> spliterator() {
            Node<K, V>[] t;
            ConcurrentHashMap<K, V> m = map;
            long n = m.sumCount();
            int f = (t = m.table) == null ? 0 : t.length;
            return new EntrySpliterator<K, V>(t, f, 0, f, n < 0L ? 0L : n, m);
        }

        public void forEach(Consumer<? super Map.Entry<K, V>> action) {
            if (action == null) throw new NullPointerException();
            Node<K, V>[] t;
            if ((t = map.table) != null) {
                Traverser<K, V> it = new Traverser<K, V>(t, t.length, 0, t.length);
                for (Node<K, V> p; (p = it.advance()) != null; )
                    action.accept(new MapEntry<K, V>(p.key, p.val, map));
            }
        }

    }

    // -------------------------------------------------------

    /**
     * Base class for bulk tasks. Repeats some fields and code from
     * class Traverser, because we need to subclass CountedCompleter.
     */
    @SuppressWarnings("serial")
    abstract static class BulkTask<K, V, R> extends CountedCompleter<R> {
        Node<K, V>[] tab;        // same as Traverser
        Node<K, V> next;
        TableStack<K, V> stack, spare;
        int index;
        int baseIndex;
        int baseLimit;
        final int baseSize;
        int batch;              // split control

        BulkTask(BulkTask<K, V, ?> par, int b, int i, int f, Node<K, V>[] t) {
            super(par);
            this.batch = b;
            this.index = this.baseIndex = i;
            if ((this.tab = t) == null)
                this.baseSize = this.baseLimit = 0;
            else if (par == null)
                this.baseSize = this.baseLimit = t.length;
            else {
                this.baseLimit = f;
                this.baseSize = par.baseSize;
            }
        }

        /**
         * Same as Traverser version
         */
        final Node<K, V> advance() {
            Node<K, V> e;
            if ((e = next) != null)
                e = e.next;
            for (; ; ) {
                Node<K, V>[] t;
                int i, n;
                if (e != null)
                    return next = e;
                if (baseIndex >= baseLimit || (t = tab) == null ||
                        (n = t.length) <= (i = index) || i < 0)
                    return next = null;
                if ((e = tabAt(t, i)) != null && e.hash < 0) {
                    if (e instanceof ForwardingNode) {
                        tab = ((ForwardingNode<K, V>) e).nextTable;
                        e = null;
                        pushState(t, i, n);
                        continue;
                    } else if (e instanceof TreeBin)
                        e = ((TreeBin<K, V>) e).first;
                    else
                        e = null;
                }
                if (stack != null)
                    recoverState(n);
                else if ((index = i + baseSize) >= n)
                    index = ++baseIndex;
            }
        }

        private void pushState(Node<K, V>[] t, int i, int n) {
            TableStack<K, V> s = spare;
            if (s != null)
                spare = s.next;
            else
                s = new TableStack<K, V>();
            s.tab = t;
            s.length = n;
            s.index = i;
            s.next = stack;
            stack = s;
        }

        private void recoverState(int n) {
            TableStack<K, V> s;
            int len;
            while ((s = stack) != null && (index += (len = s.length)) >= n) {
                n = len;
                index = s.index;
                tab = s.tab;
                s.tab = null;
                TableStack<K, V> next = s.next;
                s.next = spare; // save for reuse
                stack = next;
                spare = s;
            }
            if (s == null && (index += baseSize) >= n)
                index = ++baseIndex;
        }
    }

    /*
     * Task classes. Coded in a regular but ugly format/style to
     * simplify checks that each variant differs in the right way from
     * others. The null screenings exist because compilers cannot tell
     * that we've already null-checked task arguments, so we force
     * simplest hoisted bypass to help avoid convoluted traps.
     */
    @SuppressWarnings("serial")
    static final class ForEachKeyTask<K, V>
            extends BulkTask<K, V, Void> {
        final Consumer<? super K> action;

        ForEachKeyTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Consumer<? super K> action) {
            super(p, b, i, f, t);
            this.action = action;
        }

        public final void compute() {
            final Consumer<? super K> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachKeyTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    action.accept(p.key);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachValueTask<K, V>
            extends BulkTask<K, V, Void> {
        final Consumer<? super V> action;

        ForEachValueTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Consumer<? super V> action) {
            super(p, b, i, f, t);
            this.action = action;
        }

        public final void compute() {
            final Consumer<? super V> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachValueTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    action.accept(p.val);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachEntryTask<K, V>
            extends BulkTask<K, V, Void> {
        final Consumer<? super Entry<K, V>> action;

        ForEachEntryTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Consumer<? super Entry<K, V>> action) {
            super(p, b, i, f, t);
            this.action = action;
        }

        public final void compute() {
            final Consumer<? super Entry<K, V>> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachEntryTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    action.accept(p);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachMappingTask<K, V>
            extends BulkTask<K, V, Void> {
        final BiConsumer<? super K, ? super V> action;

        ForEachMappingTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 BiConsumer<? super K, ? super V> action) {
            super(p, b, i, f, t);
            this.action = action;
        }

        public final void compute() {
            final BiConsumer<? super K, ? super V> action;
            if ((action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachMappingTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    action.accept(p.key, p.val);
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedKeyTask<K, V, U>
            extends BulkTask<K, V, Void> {
        final Function<? super K, ? extends U> transformer;
        final Consumer<? super U> action;

        ForEachTransformedKeyTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Function<? super K, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer;
            this.action = action;
        }

        public final void compute() {
            final Function<? super K, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachTransformedKeyTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedValueTask<K, V, U>
            extends BulkTask<K, V, Void> {
        final Function<? super V, ? extends U> transformer;
        final Consumer<? super U> action;

        ForEachTransformedValueTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Function<? super V, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer;
            this.action = action;
        }

        public final void compute() {
            final Function<? super V, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachTransformedValueTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.val)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedEntryTask<K, V, U>
            extends BulkTask<K, V, Void> {
        final Function<Map.Entry<K, V>, ? extends U> transformer;
        final Consumer<? super U> action;

        ForEachTransformedEntryTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Function<Map.Entry<K, V>, ? extends U> transformer, Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer;
            this.action = action;
        }

        public final void compute() {
            final Function<Map.Entry<K, V>, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachTransformedEntryTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ForEachTransformedMappingTask<K, V, U>
            extends BulkTask<K, V, Void> {
        final BiFunction<? super K, ? super V, ? extends U> transformer;
        final Consumer<? super U> action;

        ForEachTransformedMappingTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 BiFunction<? super K, ? super V, ? extends U> transformer,
                 Consumer<? super U> action) {
            super(p, b, i, f, t);
            this.transformer = transformer;
            this.action = action;
        }

        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> transformer;
            final Consumer<? super U> action;
            if ((transformer = this.transformer) != null &&
                    (action = this.action) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    new ForEachTransformedMappingTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    transformer, action).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key, p.val)) != null)
                        action.accept(u);
                }
                propagateCompletion();
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchKeysTask<K, V, U>
            extends BulkTask<K, V, U> {
        final Function<? super K, ? extends U> searchFunction;
        final AtomicReference<U> result;

        SearchKeysTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Function<? super K, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction;
            this.result = result;
        }

        public final U getRawResult() {
            return result.get();
        }

        public final void compute() {
            final Function<? super K, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchKeysTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K, V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.key)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchValuesTask<K, V, U>
            extends BulkTask<K, V, U> {
        final Function<? super V, ? extends U> searchFunction;
        final AtomicReference<U> result;

        SearchValuesTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Function<? super V, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction;
            this.result = result;
        }

        public final U getRawResult() {
            return result.get();
        }

        public final void compute() {
            final Function<? super V, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchValuesTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K, V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.val)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchEntriesTask<K, V, U>
            extends BulkTask<K, V, U> {
        final Function<Entry<K, V>, ? extends U> searchFunction;
        final AtomicReference<U> result;

        SearchEntriesTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 Function<Entry<K, V>, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction;
            this.result = result;
        }

        public final U getRawResult() {
            return result.get();
        }

        public final void compute() {
            final Function<Entry<K, V>, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchEntriesTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K, V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        return;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class SearchMappingsTask<K, V, U>
            extends BulkTask<K, V, U> {
        final BiFunction<? super K, ? super V, ? extends U> searchFunction;
        final AtomicReference<U> result;

        SearchMappingsTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 BiFunction<? super K, ? super V, ? extends U> searchFunction,
                 AtomicReference<U> result) {
            super(p, b, i, f, t);
            this.searchFunction = searchFunction;
            this.result = result;
        }

        public final U getRawResult() {
            return result.get();
        }

        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> searchFunction;
            final AtomicReference<U> result;
            if ((searchFunction = this.searchFunction) != null &&
                    (result = this.result) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    if (result.get() != null)
                        return;
                    addToPendingCount(1);
                    new SearchMappingsTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    searchFunction, result).fork();
                }
                while (result.get() == null) {
                    U u;
                    Node<K, V> p;
                    if ((p = advance()) == null) {
                        propagateCompletion();
                        break;
                    }
                    if ((u = searchFunction.apply(p.key, p.val)) != null) {
                        if (result.compareAndSet(null, u))
                            quietlyCompleteRoot();
                        break;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ReduceKeysTask<K, V>
            extends BulkTask<K, V, K> {
        final BiFunction<? super K, ? super K, ? extends K> reducer;
        K result;
        ReduceKeysTask<K, V> rights, nextRight;

        ReduceKeysTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 ReduceKeysTask<K, V> nextRight,
                 BiFunction<? super K, ? super K, ? extends K> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.reducer = reducer;
        }

        public final K getRawResult() {
            return result;
        }

        public final void compute() {
            final BiFunction<? super K, ? super K, ? extends K> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new ReduceKeysTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, reducer)).fork();
                }
                K r = null;
                for (Node<K, V> p; (p = advance()) != null; ) {
                    K u = p.key;
                    r = (r == null) ? u : u == null ? r : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceKeysTask<K, V>
                            t = (ReduceKeysTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        K tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ReduceValuesTask<K, V>
            extends BulkTask<K, V, V> {
        final BiFunction<? super V, ? super V, ? extends V> reducer;
        V result;
        ReduceValuesTask<K, V> rights, nextRight;

        ReduceValuesTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 ReduceValuesTask<K, V> nextRight,
                 BiFunction<? super V, ? super V, ? extends V> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.reducer = reducer;
        }

        public final V getRawResult() {
            return result;
        }

        public final void compute() {
            final BiFunction<? super V, ? super V, ? extends V> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new ReduceValuesTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, reducer)).fork();
                }
                V r = null;
                for (Node<K, V> p; (p = advance()) != null; ) {
                    V v = p.val;
                    r = (r == null) ? v : reducer.apply(r, v);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceValuesTask<K, V>
                            t = (ReduceValuesTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        V tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class ReduceEntriesTask<K, V>
            extends BulkTask<K, V, Map.Entry<K, V>> {
        final BiFunction<Map.Entry<K, V>, Map.Entry<K, V>, ? extends Map.Entry<K, V>> reducer;
        Map.Entry<K, V> result;
        ReduceEntriesTask<K, V> rights, nextRight;

        ReduceEntriesTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 ReduceEntriesTask<K, V> nextRight,
                 BiFunction<Entry<K, V>, Map.Entry<K, V>, ? extends Map.Entry<K, V>> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.reducer = reducer;
        }

        public final Map.Entry<K, V> getRawResult() {
            return result;
        }

        public final void compute() {
            final BiFunction<Map.Entry<K, V>, Map.Entry<K, V>, ? extends Map.Entry<K, V>> reducer;
            if ((reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new ReduceEntriesTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, reducer)).fork();
                }
                Map.Entry<K, V> r = null;
                for (Node<K, V> p; (p = advance()) != null; )
                    r = (r == null) ? p : reducer.apply(r, p);
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    ReduceEntriesTask<K, V>
                            t = (ReduceEntriesTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        Map.Entry<K, V> tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysTask<K, V, U>
            extends BulkTask<K, V, U> {
        final Function<? super K, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceKeysTask<K, V, U> rights, nextRight;

        MapReduceKeysTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceKeysTask<K, V, U> nextRight,
                 Function<? super K, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }

        public final U getRawResult() {
            return result;
        }

        public final void compute() {
            final Function<? super K, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysTask<K, V, U>
                            t = (MapReduceKeysTask<K, V, U>) c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesTask<K, V, U>
            extends BulkTask<K, V, U> {
        final Function<? super V, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceValuesTask<K, V, U> rights, nextRight;

        MapReduceValuesTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceValuesTask<K, V, U> nextRight,
                 Function<? super V, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }

        public final U getRawResult() {
            return result;
        }

        public final void compute() {
            final Function<? super V, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.val)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesTask<K, V, U>
                            t = (MapReduceValuesTask<K, V, U>) c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesTask<K, V, U>
            extends BulkTask<K, V, U> {
        final Function<Map.Entry<K, V>, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceEntriesTask<K, V, U> rights, nextRight;

        MapReduceEntriesTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceEntriesTask<K, V, U> nextRight,
                 Function<Map.Entry<K, V>, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }

        public final U getRawResult() {
            return result;
        }

        public final void compute() {
            final Function<Map.Entry<K, V>, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesTask<K, V, U>
                            t = (MapReduceEntriesTask<K, V, U>) c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsTask<K, V, U>
            extends BulkTask<K, V, U> {
        final BiFunction<? super K, ? super V, ? extends U> transformer;
        final BiFunction<? super U, ? super U, ? extends U> reducer;
        U result;
        MapReduceMappingsTask<K, V, U> rights, nextRight;

        MapReduceMappingsTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceMappingsTask<K, V, U> nextRight,
                 BiFunction<? super K, ? super V, ? extends U> transformer,
                 BiFunction<? super U, ? super U, ? extends U> reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.reducer = reducer;
        }

        public final U getRawResult() {
            return result;
        }

        public final void compute() {
            final BiFunction<? super K, ? super V, ? extends U> transformer;
            final BiFunction<? super U, ? super U, ? extends U> reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsTask<K, V, U>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, reducer)).fork();
                }
                U r = null;
                for (Node<K, V> p; (p = advance()) != null; ) {
                    U u;
                    if ((u = transformer.apply(p.key, p.val)) != null)
                        r = (r == null) ? u : reducer.apply(r, u);
                }
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsTask<K, V, U>
                            t = (MapReduceMappingsTask<K, V, U>) c,
                            s = t.rights;
                    while (s != null) {
                        U tr, sr;
                        if ((sr = s.result) != null)
                            t.result = (((tr = t.result) == null) ? sr :
                                    reducer.apply(tr, sr));
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysToDoubleTask<K, V>
            extends BulkTask<K, V, Double> {
        final ToDoubleFunction<? super K> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceKeysToDoubleTask<K, V> rights, nextRight;

        MapReduceKeysToDoubleTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceKeysToDoubleTask<K, V> nextRight,
                 ToDoubleFunction<? super K> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Double getRawResult() {
            return result;
        }

        public final void compute() {
            final ToDoubleFunction<? super K> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToDoubleTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToDoubleTask<K, V>
                            t = (MapReduceKeysToDoubleTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesToDoubleTask<K, V>
            extends BulkTask<K, V, Double> {
        final ToDoubleFunction<? super V> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceValuesToDoubleTask<K, V> rights, nextRight;

        MapReduceValuesToDoubleTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceValuesToDoubleTask<K, V> nextRight,
                 ToDoubleFunction<? super V> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Double getRawResult() {
            return result;
        }

        public final void compute() {
            final ToDoubleFunction<? super V> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToDoubleTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToDoubleTask<K, V>
                            t = (MapReduceValuesToDoubleTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesToDoubleTask<K, V>
            extends BulkTask<K, V, Double> {
        final ToDoubleFunction<Map.Entry<K, V>> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceEntriesToDoubleTask<K, V> rights, nextRight;

        MapReduceEntriesToDoubleTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceEntriesToDoubleTask<K, V> nextRight,
                 ToDoubleFunction<Map.Entry<K, V>> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Double getRawResult() {
            return result;
        }

        public final void compute() {
            final ToDoubleFunction<Map.Entry<K, V>> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToDoubleTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToDoubleTask<K, V>
                            t = (MapReduceEntriesToDoubleTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsToDoubleTask<K, V>
            extends BulkTask<K, V, Double> {
        final ToDoubleBiFunction<? super K, ? super V> transformer;
        final DoubleBinaryOperator reducer;
        final double basis;
        double result;
        MapReduceMappingsToDoubleTask<K, V> rights, nextRight;

        MapReduceMappingsToDoubleTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceMappingsToDoubleTask<K, V> nextRight,
                 ToDoubleBiFunction<? super K, ? super V> transformer,
                 double basis,
                 DoubleBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Double getRawResult() {
            return result;
        }

        public final void compute() {
            final ToDoubleBiFunction<? super K, ? super V> transformer;
            final DoubleBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                double r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToDoubleTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsDouble(r, transformer.applyAsDouble(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToDoubleTask<K, V>
                            t = (MapReduceMappingsToDoubleTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsDouble(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysToLongTask<K, V>
            extends BulkTask<K, V, Long> {
        final ToLongFunction<? super K> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceKeysToLongTask<K, V> rights, nextRight;

        MapReduceKeysToLongTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceKeysToLongTask<K, V> nextRight,
                 ToLongFunction<? super K> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Long getRawResult() {
            return result;
        }

        public final void compute() {
            final ToLongFunction<? super K> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToLongTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToLongTask<K, V>
                            t = (MapReduceKeysToLongTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesToLongTask<K, V>
            extends BulkTask<K, V, Long> {
        final ToLongFunction<? super V> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceValuesToLongTask<K, V> rights, nextRight;

        MapReduceValuesToLongTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceValuesToLongTask<K, V> nextRight,
                 ToLongFunction<? super V> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Long getRawResult() {
            return result;
        }

        public final void compute() {
            final ToLongFunction<? super V> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToLongTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToLongTask<K, V>
                            t = (MapReduceValuesToLongTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesToLongTask<K, V>
            extends BulkTask<K, V, Long> {
        final ToLongFunction<Map.Entry<K, V>> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceEntriesToLongTask<K, V> rights, nextRight;

        MapReduceEntriesToLongTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceEntriesToLongTask<K, V> nextRight,
                 ToLongFunction<Map.Entry<K, V>> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Long getRawResult() {
            return result;
        }

        public final void compute() {
            final ToLongFunction<Map.Entry<K, V>> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToLongTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToLongTask<K, V>
                            t = (MapReduceEntriesToLongTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsToLongTask<K, V>
            extends BulkTask<K, V, Long> {
        final ToLongBiFunction<? super K, ? super V> transformer;
        final LongBinaryOperator reducer;
        final long basis;
        long result;
        MapReduceMappingsToLongTask<K, V> rights, nextRight;

        MapReduceMappingsToLongTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceMappingsToLongTask<K, V> nextRight,
                 ToLongBiFunction<? super K, ? super V> transformer,
                 long basis,
                 LongBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Long getRawResult() {
            return result;
        }

        public final void compute() {
            final ToLongBiFunction<? super K, ? super V> transformer;
            final LongBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                long r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToLongTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsLong(r, transformer.applyAsLong(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToLongTask<K, V>
                            t = (MapReduceMappingsToLongTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsLong(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceKeysToIntTask<K, V>
            extends BulkTask<K, V, Integer> {
        final ToIntFunction<? super K> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceKeysToIntTask<K, V> rights, nextRight;

        MapReduceKeysToIntTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceKeysToIntTask<K, V> nextRight,
                 ToIntFunction<? super K> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Integer getRawResult() {
            return result;
        }

        public final void compute() {
            final ToIntFunction<? super K> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceKeysToIntTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.key));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceKeysToIntTask<K, V>
                            t = (MapReduceKeysToIntTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceValuesToIntTask<K, V>
            extends BulkTask<K, V, Integer> {
        final ToIntFunction<? super V> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceValuesToIntTask<K, V> rights, nextRight;

        MapReduceValuesToIntTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceValuesToIntTask<K, V> nextRight,
                 ToIntFunction<? super V> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Integer getRawResult() {
            return result;
        }

        public final void compute() {
            final ToIntFunction<? super V> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceValuesToIntTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceValuesToIntTask<K, V>
                            t = (MapReduceValuesToIntTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceEntriesToIntTask<K, V>
            extends BulkTask<K, V, Integer> {
        final ToIntFunction<Map.Entry<K, V>> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceEntriesToIntTask<K, V> rights, nextRight;

        MapReduceEntriesToIntTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceEntriesToIntTask<K, V> nextRight,
                 ToIntFunction<Map.Entry<K, V>> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Integer getRawResult() {
            return result;
        }

        public final void compute() {
            final ToIntFunction<Map.Entry<K, V>> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceEntriesToIntTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceEntriesToIntTask<K, V>
                            t = (MapReduceEntriesToIntTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    @SuppressWarnings("serial")
    static final class MapReduceMappingsToIntTask<K, V>
            extends BulkTask<K, V, Integer> {
        final ToIntBiFunction<? super K, ? super V> transformer;
        final IntBinaryOperator reducer;
        final int basis;
        int result;
        MapReduceMappingsToIntTask<K, V> rights, nextRight;

        MapReduceMappingsToIntTask
                (BulkTask<K, V, ?> p, int b, int i, int f, Node<K, V>[] t,
                 MapReduceMappingsToIntTask<K, V> nextRight,
                 ToIntBiFunction<? super K, ? super V> transformer,
                 int basis,
                 IntBinaryOperator reducer) {
            super(p, b, i, f, t);
            this.nextRight = nextRight;
            this.transformer = transformer;
            this.basis = basis;
            this.reducer = reducer;
        }

        public final Integer getRawResult() {
            return result;
        }

        public final void compute() {
            final ToIntBiFunction<? super K, ? super V> transformer;
            final IntBinaryOperator reducer;
            if ((transformer = this.transformer) != null &&
                    (reducer = this.reducer) != null) {
                int r = this.basis;
                for (int i = baseIndex, f, h; batch > 0 &&
                        (h = ((f = baseLimit) + i) >>> 1) > i; ) {
                    addToPendingCount(1);
                    (rights = new MapReduceMappingsToIntTask<K, V>
                            (this, batch >>>= 1, baseLimit = h, f, tab,
                                    rights, transformer, r, reducer)).fork();
                }
                for (Node<K, V> p; (p = advance()) != null; )
                    r = reducer.applyAsInt(r, transformer.applyAsInt(p.key, p.val));
                result = r;
                CountedCompleter<?> c;
                for (c = firstComplete(); c != null; c = c.nextComplete()) {
                    @SuppressWarnings("unchecked")
                    MapReduceMappingsToIntTask<K, V>
                            t = (MapReduceMappingsToIntTask<K, V>) c,
                            s = t.rights;
                    while (s != null) {
                        t.result = reducer.applyAsInt(t.result, s.result);
                        s = t.rights = s.nextRight;
                    }
                }
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long SIZECTL;
    private static final long TRANSFERINDEX;
    private static final long BASECOUNT;
    private static final long CELLSBUSY;
    private static final long CELLVALUE;
    private static final long ABASE;
    private static final int ASHIFT;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = ConcurrentHashMap.class;
            SIZECTL = U.objectFieldOffset
                    (k.getDeclaredField("sizeCtl"));
            TRANSFERINDEX = U.objectFieldOffset
                    (k.getDeclaredField("transferIndex"));
            BASECOUNT = U.objectFieldOffset
                    (k.getDeclaredField("baseCount"));
            CELLSBUSY = U.objectFieldOffset
                    (k.getDeclaredField("cellsBusy"));
            Class<?> ck = CounterCell.class;
            CELLVALUE = U.objectFieldOffset
                    (ck.getDeclaredField("value"));
            Class<?> ak = Node[].class;
            ABASE = U.arrayBaseOffset(ak);
            int scale = U.arrayIndexScale(ak);
            if ((scale & (scale - 1)) != 0)
                throw new Error("data type scale not a power of two");
            ASHIFT = 31 - Integer.numberOfLeadingZeros(scale);
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
