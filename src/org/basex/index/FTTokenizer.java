package org.basex.index;

import static org.basex.util.Token.*;
import org.basex.core.Prop;
import org.basex.query.ft.FTOpt;
import org.basex.util.IntList;
import org.basex.util.Map;
import org.basex.util.Stemming;
import org.basex.util.TokenBuilder;
import org.basex.util.TokenList;

/**
 * Full-text tokenizer.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public final class FTTokenizer extends IndexToken {
  /** Stemming instance. */
  private final Stemming stem = new Stemming();
  /** Stemming dictionary. */
  public Map<byte[]> sd;
  /** Stemming flag. */
  public boolean st = Prop.ftst;
  /** Diacritics flag. */
  public boolean dc = Prop.ftdc;
  /** Sensitivity flag. */
  public boolean cs = Prop.ftcs;
  /** Uppercase flag. */
  public boolean uc;
  /** Lowercase flag. */
  public boolean lc;
  /** Wildcard flag. */
  public boolean wc;
  /** Fuzzy flag. */
  public boolean fz;
  /** Flag for loading ftposition data. */
  public boolean lp;

  /** Current sentence. */
  public int sent;
  /** Current paragraph. */
  public int para;
  /** Current token. */
  public int pos = -1;
  /** Current character position. */
  public int p;
  /** Character start position. */
  public int s;
  /** Number of tokens. */
  public int count = -1;
  /** Backup last punctuation mark. */
  public int lastpm;

  /**
   * Empty constructor.
   */
  public FTTokenizer() {
    super(Type.FTX);
  }

  /**
   * Constructor.
   * @param txt text
   */
  public FTTokenizer(final byte[] txt) {
    this();
    text = txt;
  }

  /**
   * Constructor.
   * @param txt text
   * @param fto fulltext options
   */
  public FTTokenizer(final byte[] txt, final FTOpt fto) {
    this(txt);
    lc = fto.is(FTOpt.LC);
    uc = fto.is(FTOpt.UC);
    cs = fto.is(FTOpt.CS);
    wc = fto.is(FTOpt.WC);
    fz = fto.is(FTOpt.FZ);
    sd = fto.sd;
  }

  /**
   * Sets the text.
   * @param txt text
   */
  public void init(final byte[] txt) {
    if(text != txt) count = -1;
    text = txt;
    init();
  }

  /**
   * Initializes the iterator.
   */
  public void init() {
    sent = 0;
    para = 0;
    pos = -1;
    p = 0;
  }

  /**
   * Checks if more tokens are to be returned.
   * @return result of check
   */
  public boolean more() {
    if (text == null) return false;
    final int l = text.length;
    pos++;

    // parse whitespaces
    boolean sn = false;
    boolean pa = false;
    for(; p < l; p += cl(text[p])) {
      final int c = cp(text, p);
      if(wc && c == '.') break;

      if(!sn && (c == '.' || c == '!' || c == '?')) {
        sn = true;
        sent++;
        lastpm = c;
      } else if(!pa && c == '\n') {
        pa = true;
        para++;
      } else if(c >= '0' && (letterOrDigit(c)  ||
          Character.isLetterOrDigit(c))) {
        break;
      }
    }
    // end of text...
    s = p;
    if(p == l) return false;

    // parse token
    for(; p < l; p += cl(text[p])) {
      final int c = cp(text, p);
      if(c < '0' || !letterOrDigit(c) && !Character.isLetterOrDigit(c)) {
        // [CG] FT/parse wildcard indicators
        if(!wc || ws(c)) break;
      }
    }
    return true;
  }
  
  @Override
  public byte[] get() {
    byte[] n = substring(text, s, p);
    final boolean a = ascii(n);
    if(wc) n = wc(n);
    if(!dc) n = dia(n, a);
    if(uc) n = upper(n, a);
    if(lc || !cs) n = lower(n, a);
    if(st) n = stem(n);
    return n;
  }

  /**
   * Counts the number of tokens.
   * @return number of tokens
   */
  public int count() {
    if(count == -1) {
      init();
      while(more());
      count = pos;
    }
    return count;
  }

  /**
   * Gets fulltext info out of text.
   * int[0]: length of each token
   * int[1]: sentence info, length of each sentence
   * int[2]: paragraph info, length of each paragraph
   * int[3]: each token as int[]
   * int[4]: punctuation marks of each sentence  
   * @return int arrays
   */
  public int[][] getInfo() {
    final IntList[] il = new IntList[] { new IntList(), new IntList(),
        new IntList(), new IntList(), new IntList()};
    int lass = 0;
    int lasp = 0;
    int sl = 0;
    int pl = 0;
    while(more()) {
      final byte[] n = get();
      final int l = n.length;
      il[0].add(l);
      il[3].add(n);

      if(sent != lass) {
        if(sl > 0) {
          il[1].add(sl);
          il[4].add(lastpm);
        }
        lass = sent;
        sl = 0;
      }
      if(para != lasp) {
        if(pl > 0) il[2].add(pl);
        lasp = para;
        pl = 0;
      }

      sl += l;
      pl += l;
    }

    if(sent != lass && sl > 0) {
      il[1].add(sl);
      il[4].add(lastpm);
    }
    if(pl > 0) il[2].add(pl);

    // last sentence not finished with a punctuation mark
    il[1].add(sl + 1);

    return new int[][] { il[0].finish(), il[1].finish(), il[2].finish(),
        il[3].finish(), il[4].finish()};
  }
  
  /**
   * Removes diacritics from the specified token.
   * Note that this method does only support the first 256 unicode characters.
   * @param t token to be converted
   * @param ascii ascii flag
   * @return converted token
   */
  private static byte[] dia(final byte[] t, final boolean ascii) {
    if(ascii) return t;
    final String s = utf8(t, 0, t.length);
    final StringBuilder sb = new StringBuilder();
    final int jl = s.length();
    for(int j = 0; j < jl; j++) {
      final char c = s.charAt(j);
      sb.append(c < 192 || c > 255 ? c : (char) NORM[c - 192]);
    }
    return token(sb.toString());
  }

  /**
   * Converts the specified token to upper case.
   * @param t token to be converted
   * @param a ascii flag
   * @return the converted token
   */
  private static byte[] upper(final byte[] t, final boolean a) {
    if(!a) return token(string(t).toUpperCase());
    for(int i = 0; i < t.length; i++) t[i] = (byte) uc(t[i]);
    return t;
  }

  /**
   * Converts the specified token to lower case.
   * @param t token to be converted
   * @param a ascii flag
   * @return the converted token
   */
  private static byte[] lower(final byte[] t, final boolean a) {
    if(!a) return token(string(t).toLowerCase());
    for(int i = 0; i < t.length; i++) t[i] = (byte) lc(t[i]);
    return t;
  }

  /**
   * Stems the specified token.
   * @param t token to be converted
   * @return the converted token
   */
  private byte[] stem(final byte[] t) {
    if(sd == null) return stem.word(t);
    final byte[] sn = sd.get(t);
    return sn != null ? sn : t;
  }
  
  /**
   * Returns a wildcard token.
   * @param n input token
   * @return resulting token
   */
  private static byte[] wc(final byte[] n) {
    if(!contains(n, '\\')) return n;
    final TokenBuilder tb = new TokenBuilder();
    boolean bs = false;
    for(final byte c : n) {
      if(c == '\\') {
        bs = true;
      } else if(bs) {
        if(Character.isLetterOrDigit(c)) tb.add(c);
        bs = false;
      } else {
        tb.add(c);
      }
    }
    return tb.finish();
  }

  /**
   * Returns the text size.
   * @return size
   */
  public int size() {
    return text.length;
  }

  /**
   * Converts the tokens to a TokenList.
   * @return TokenList
   */
  public TokenList getTokenList() {
    final TokenList tl = new TokenList();
    init();
    while(more()) tl.add(get());
    return tl;
  }

  @Override
  public String toString() {
    return "FTTokenizer[" + string(text) + "]";
  }
}
