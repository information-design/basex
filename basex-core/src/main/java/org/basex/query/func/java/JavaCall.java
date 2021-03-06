package org.basex.query.func.java;

import static org.basex.query.QueryError.*;
import static org.basex.query.QueryText.*;
import static org.basex.util.Token.*;

import java.lang.reflect.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.*;

import javax.xml.datatype.*;
import javax.xml.namespace.*;

import org.basex.core.locks.*;
import org.basex.core.users.*;
import org.basex.query.*;
import org.basex.query.QueryModule.*;
import org.basex.query.expr.*;
import org.basex.query.func.*;
import org.basex.query.iter.*;
import org.basex.query.util.*;
import org.basex.query.util.pkg.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.seq.*;
import org.basex.query.value.type.*;
import org.basex.query.value.type.Type;
import org.basex.util.*;
import org.basex.util.list.*;
import org.basex.util.similarity.*;
import org.w3c.dom.*;

/**
 * This class contains common methods for executing Java code and mapping
 * Java objects to XQuery values.
 *
 * @author BaseX Team 2005-19, BSD License
 * @author Christian Gruen
 */
public abstract class JavaCall extends Arr {
  /** Static context. */
  final StaticContext sc;
  /** Permission. */
  final Perm perm;

  /**
   * Constructor.
   * @param args arguments
   * @param perm required permission to run the function
   * @param sc static context
   * @param info input info
   */
  JavaCall(final Expr[] args, final Perm perm, final StaticContext sc, final InputInfo info) {
    super(info, SeqType.ITEM_ZM, args);
    this.sc = sc;
    this.perm = perm;
  }

  @Override
  public final Value value(final QueryContext qc) throws QueryException {
    // check permission
    if(!qc.context.user().has(perm)) throw BASEX_PERMISSION_X_X.get(info, perm, this);
    return toValue(eval(qc), qc, sc);
  }

  /**
   * Returns the result of the evaluated Java function.
   * @param qc query context
   * @return arguments
   * @throws QueryException query exception
   */
  protected abstract Object eval(QueryContext qc) throws QueryException;

  @Override
  public boolean has(final Flag... flags) {
    return Flag.NDT.in(flags) || super.has(flags);
  }

  // STATIC METHODS ===============================================================================

  /**
   * Converts the specified result to an XQuery value.
   * @param object result object
   * @param qc query context
   * @param sc static context
   * @return value
   * @throws QueryException query exception
   */
  public static Value toValue(final Object object, final QueryContext qc, final StaticContext sc)
      throws QueryException {

    if(object == null) return Empty.VALUE;
    if(object instanceof Value) return (Value) object;
    if(object instanceof Iter) return ((Iter) object).value(qc, null);
    // find XQuery mapping for specified type
    final Type type = type(object);
    if(type != null) return type.cast(object, qc, sc, null);

    // primitive arrays
    if(object instanceof byte[])    return BytSeq.get((byte[]) object);
    if(object instanceof long[])    return IntSeq.get((long[]) object);
    if(object instanceof char[])    return Str.get(new String((char[]) object));
    if(object instanceof boolean[]) return BlnSeq.get((boolean[]) object);
    if(object instanceof double[])  return DblSeq.get((double[]) object);
    if(object instanceof float[])   return FltSeq.get((float[]) object);

    // no array: return Java type
    if(!object.getClass().isArray()) return new Jav(object, qc);

    // empty array
    final int s = Array.getLength(object);
    if(s == 0) return Empty.VALUE;
    // string array
    if(object instanceof String[]) {
      final String[] r = (String[]) object;
      final byte[][] b = new byte[r.length][];
      for(int v = 0; v < s; v++) b[v] = token(r[v]);
      return StrSeq.get(b);
    }
    // character array
    if(object instanceof char[][]) {
      final char[][] r = (char[][]) object;
      final byte[][] b = new byte[r.length][];
      for(int v = 0; v < s; v++) b[v] = token(new String(r[v]));
      return StrSeq.get(b);
    }
    // short array
    if(object instanceof short[]) {
      final short[] r = (short[]) object;
      final long[] b = new long[r.length];
      for(int v = 0; v < s; v++) b[v] = r[v];
      return IntSeq.get(b, AtomType.SHR);
    }
    // integer array
    if(object instanceof int[]) {
      final int[] r = (int[]) object;
      final long[] b = new long[r.length];
      for(int v = 0; v < s; v++) b[v] = r[v];
      return IntSeq.get(b, AtomType.INT);
    }
    // any other array (also nested ones)
    final ValueBuilder vb = new ValueBuilder(qc);
    for(final Object obj : (Object[]) object) vb.add(toValue(obj, qc, sc));
    return vb.value();
  }

  /**
   * Returns a new Java function instance.
   * @param qname function name
   * @param args arguments
   * @param qc query context
   * @param sc static context
   * @param ii input info
   * @return Java function or {@code null}
   * @throws QueryException query exception
   */
  public static JavaCall get(final QNm qname, final Expr[] args, final QueryContext qc,
      final StaticContext sc, final InputInfo ii) throws QueryException {

    // rewrite function name, extract argument types
    String name = Strings.camelCase(string(qname.local()));
    String[] types = null;
    final int n = name.indexOf('\u00b7');
    if(n != -1) {
      types = Strings.split(name.substring(n + 1), '\u00b7');
      name = name.substring(0, n);
    }
    final String uri = string(qname.uri());

    // check if URI starts with "java:" prefix. if yes, skip rewritings
    final boolean enforce = uri.startsWith(JAVAPREF);
    final String className = enforce ? uri.substring(JAVAPREF.length()) :
      Strings.className(Strings.uri2path(uri));

    // function in imported Java module
    final ModuleLoader modules = qc.resources.modules();
    final Object module  = modules.findModule(className);
    if(module != null) {
      final Method meth = moduleMethod(module, name, args.length, types, qname, qc, ii);
      final Requires req = meth.getAnnotation(Requires.class);
      final Perm perm = req == null ? Perm.ADMIN :
        Perm.get(req.value().name().toLowerCase(Locale.ENGLISH));
      return new StaticJavaCall(module, meth, args, perm, sc, ii);
    }

    /* skip Java class lookup if...
     * - no java prefix was supplied, and
     * - if URI equals namespace of library module or if it is globally declared
     *
     * examples:
     * - declare function local:f($i) { if($i) then local:f($i - 1) else () }
     * - module namespace _ = '_'; declare function _:_() { _:_() };
     * - fn:does-not-exist(), util:not-available()
     */
    if(enforce || (sc.module == null || !eq(sc.module.uri(), qname.uri())) &&
        NSGlobal.prefix(qname.uri()).length == 0) {

      // Java constructor, function, or variable
      Class<?> clazz = null;
      try {
        clazz = modules.findClass(className);
      } catch(final ClassNotFoundException ex) {
        if(enforce) Util.debug(ex);
      } catch(final Throwable th) {
        // catch linkage and other errors as well
        throw JAVAINIT_X_X.get(ii, Util.className(th), th);
      }

      if(clazz == null) {
        // class not found, java prefix was specified
        if(enforce) throw WHICHCLASS_X.get(ii, className);
      } else {
        // constructor
        if(name.equals(NEW)) {
          final DynJavaConstr djc = new DynJavaConstr(clazz, types, args, sc, ii);
          if(djc.init(enforce)) return djc;
        }
        // field or method
        final DynJavaFunc djf = new DynJavaFunc(clazz, name, types, args, sc, ii);
        if(djf.init(enforce)) return djf;
      }
    }

    return null;
  }

  /**
   * Gets the specified method from a query module.
   * @param module query module object
   * @param name method name
   * @param arity number of arguments
   * @param types types provided in the query (can be {@code null})
   * @param qname original name
   * @param qc query context
   * @param ii input info
   * @return method if found
   * @throws QueryException query exception
   */
  private static Method moduleMethod(final Object module, final String name, final int arity,
      final String[] types, final QNm qname, final QueryContext qc, final InputInfo ii)
      throws QueryException {

    // find method with identical name and arity
    Method method = null;
    final IntList arities = new IntList();
    final Method[] methods = module.getClass().getMethods();
    for(final Method m : methods) {
      if(!m.getName().equals(name)) continue;
      final Class<?>[] pTypes = m.getParameterTypes();
      final int mArity = pTypes.length;
      if(mArity == arity) {
        if(typesMatch(pTypes, types)) {
          if(method != null) throw JAVAMULTIFUNC_X_X.get(ii, qname.string(), arguments(arity));
          method = m;
        }
      } else if(types == null) {
        arities.add(mArity);
      }
    }

    // method found: add module locks to QueryContext
    if(method != null) {
      final Lock lock = method.getAnnotation(Lock.class);
      if(lock != null) {
        for(final String read : lock.read()) qc.readLocks.add(Locking.JAVA_PREFIX + read);
        for(final String write : lock.write()) qc.writeLocks.add(Locking.JAVA_PREFIX + write);
      }
      return method;
    }

    // no suitable method found: check if method with correct name was found
    throw noFunction(name, arity, string(qname.string()), arities, types, ii, list -> {
      for(final Method m : methods) list.add(m.getName());
    });
  }

  /**
   * Returns an error message if no fields and methods could be chosen for execution.
   * @param name name of field or method
   * @param arity supplied arity
   * @param full full name of field or method
   * @param arities arities of found methods
   * @param types types (can be {@code null})
   * @param ii input info
   * @param consumer list of available names
   * @return exception
   */
  static QueryException noFunction(final String name, final int arity, final String full,
      final IntList arities, final String[] types, final InputInfo ii,
      final Consumer<TokenList> consumer) {

    // functions with different arities
    if(!arities.isEmpty()) return Functions.wrongArity(full, arity, arities, ii);

    // find similar field/method names
    final byte[] nm = token(name), similar = Levenshtein.similar(nm, consumer);
    if(similar != null) {
      // if name is equal, no function was chosen via exact type matching
      if(eq(nm, similar)) {
        final StringBuilder sb = new StringBuilder();
        for(final String type : types) {
          if(sb.length() != 0) sb.append(", ");
          sb.append(type.replaceAll("^.*\\.", ""));
        }
        return JAVAARGS_X_X.get(ii, full, sb);
      }
      // show similar field/method name
      return FUNCSIMILAR_X_X.get(ii, full, similar);
    }
    // no similar field/method found, show default error
    return WHICHFUNC_X.get(ii, full);
  }

  /**
   * Compares the types of method parameters with the specified types.
   * @param pTypes parameter types
   * @param qTypes query types (can be {@code null})
   * @return result of check
   */
  protected static boolean typesMatch(final Class<?>[] pTypes, final String[] qTypes) {
    // no query types: accept method
    if(qTypes == null) return true;
    // compare types
    final int pl = pTypes.length;
    if(pl != qTypes.length) return false;
    for(int p = 0; p < pl; p++) {
      if(!qTypes[p].equals(pTypes[p].getName())) return false;
    }
    return true;
  }

  /**
   * Returns an appropriate XQuery type for the specified Java object.
   * @param object object
   * @return item type or {@code null} if no appropriate type was found
   */
  private static Type type(final Object object) {
    final Type type = JavaMapping.type(object.getClass(), true);
    if(type != null) return type;

    if(object instanceof Element) return NodeType.ELM;
    if(object instanceof Document) return NodeType.DOC;
    if(object instanceof DocumentFragment) return NodeType.DOC;
    if(object instanceof Attr) return NodeType.ATT;
    if(object instanceof Comment) return NodeType.COM;
    if(object instanceof ProcessingInstruction) return NodeType.PI;
    if(object instanceof Text) return NodeType.TXT;

    if(object instanceof Duration) {
      final Duration d = (Duration) object;
      return !d.isSet(DatatypeConstants.YEARS) && !d.isSet(DatatypeConstants.MONTHS)
          ? AtomType.DTD : !d.isSet(DatatypeConstants.HOURS) &&
          !d.isSet(DatatypeConstants.MINUTES) && !d.isSet(DatatypeConstants.SECONDS)
          ? AtomType.YMD : AtomType.DUR;
    }

    if(object instanceof XMLGregorianCalendar) {
      final QName qnm = ((XMLGregorianCalendar) object).getXMLSchemaType();
      if(qnm == DatatypeConstants.DATE) return AtomType.DAT;
      if(qnm == DatatypeConstants.DATETIME) return AtomType.DTM;
      if(qnm == DatatypeConstants.TIME) return AtomType.TIM;
      if(qnm == DatatypeConstants.GYEARMONTH) return AtomType.YMO;
      if(qnm == DatatypeConstants.GMONTHDAY) return AtomType.MDA;
      if(qnm == DatatypeConstants.GYEAR) return AtomType.YEA;
      if(qnm == DatatypeConstants.GMONTH) return AtomType.MON;
      if(qnm == DatatypeConstants.GDAY) return AtomType.DAY;
    }
    return null;
  }

  /**
   * Returns an XQuery string representation of the Java entity of this expression.
   * @return string
   */
  abstract String desc();

  /**
   * Returns the name of the Java entity.
   * @return string
   */
  abstract String name();

  @Override
  public final String description() {
    return desc() + "(...)";
  }

  @Override
  public final void plan(final QueryPlan plan) {
    plan.add(plan.create(this, NAME, name()), exprs);
  }

  @Override
  public final String toString() {
    return desc() + toString(SEP);
  }
}
