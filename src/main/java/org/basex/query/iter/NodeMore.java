package org.basex.query.iter;

/**
 * Iterator interface, extending the default iterator with a {@link #more}
 * method.
 *
 * @author BaseX Team 2005-11, BSD License
 * @author Christian Gruen
 */
public abstract class NodeMore extends NodeIter {
  /**
   * Checks if more nodes are found.
   * @return temporary node
   */
  public abstract boolean more();
}
