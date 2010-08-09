package org.basex.query.path;

import java.io.IOException;
import org.basex.data.Serializer;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.expr.Context;
import org.basex.query.expr.Expr;
import org.basex.query.expr.ParseExpr;
import org.basex.query.expr.Root;
import org.basex.query.item.Value;
import org.basex.query.util.Var;
import org.basex.util.InputInfo;

/**
 * Path expression.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-10, ISC License
 * @author Christian Gruen
 */
abstract class Path extends ParseExpr {
  /** Top expression. */
  public Expr root;

  /**
   * Constructor.
   * @param ii input info
   * @param r root expression; can be null
   */
  protected Path(final InputInfo ii, final Expr r) {
    super(ii);
    root = r;
  }

  @Override
  public final Expr comp(final QueryContext ctx) throws QueryException {
    if(root != null) root = checkUp(root, ctx).comp(ctx);
    final Value vi = ctx.value;
    ctx.value = root(ctx);
    final Expr e = compPath(ctx);
    ctx.value = vi;
    if(root instanceof Context) root = null;
    return e;
  }

  /**
   * Compiles the location path.
   * @param ctx query context
   * @return optimized Expression
   * @throws QueryException query exception
   */
  protected abstract Expr compPath(final QueryContext ctx)
    throws QueryException;

  /**
   * Returns the root of the current context, or null.
   * @param ctx query context
   * @return root
   */
  protected final Value root(final QueryContext ctx) {
    final Value v = ctx != null ? ctx.value : null;
    if(root == null) return v;
    if(root.value()) return (Value) root;
    if(!(root instanceof Root) || v == null) return null;
    return v.size(ctx) != 1 ? v : ((Root) root).root(v);
  }

  /**
   * Position test.
   * @param step step array
   * @param use use type
   * @param ctx query context
   * @return result of check
   */
  protected final boolean uses(final Expr[] step, final Use use,
      final QueryContext ctx) {

    if(use == Use.CTX || use == Use.ELM)
      return root == null || root.uses(use, ctx);

    for(final Expr s : step) if(s.uses(use, ctx)) return true;
    return root != null && root.uses(use, ctx);
  }

  @Override
  public Expr remove(final Var v) {
    if(root != null) root = root.remove(v);
    if(root instanceof Context) root = null;
    return this;
  }

  @Override
  public final String color() {
    return "FFCC33";
  }

  /**
   * Prints the query plan.
   * @param ser serializer
   * @param step step array
   * @throws IOException I/O exception
   */
  final void plan(final Serializer ser, final Expr[] step) throws IOException {
    ser.openElement(this);
    if(root != null) root.plan(ser);
    for(final Expr s : step) s.plan(ser);
    ser.closeElement();
  }

  /**
   * Returns a string representation.
   * @param step step array
   * @return string representation
   */
  protected final String toString(final Expr[] step) {
    final StringBuilder sb = new StringBuilder();
    if(root != null) sb.append(root);
    for(final Expr s : step) sb.append((sb.length() != 0 ? "/" : "") + s);
    return sb.toString();
  }
}
