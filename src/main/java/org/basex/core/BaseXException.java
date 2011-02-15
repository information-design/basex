package org.basex.core;

import org.basex.util.Util;

/**
 * This is a simple container for sessions.
 *
 * @author BaseX Team 2005-11, BSD License
 * @author Christian Gruen
 */
public final class BaseXException extends Exception {
  /**
   * Constructs an exception with the specified message and extension.
   * @param s message
   * @param e message extension
   */
  public BaseXException(final String s, final Object... e) {
    super(Util.info(s, e));
  }

  /**
   * Constructs an exception from the specified exception instance.
   * @param ex exception
   */
  public BaseXException(final Exception ex) {
    this(ex.getMessage() != null ? ex.getMessage() : ex.toString());
  }
}
