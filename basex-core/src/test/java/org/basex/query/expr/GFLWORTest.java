package org.basex.query.expr;

import static org.basex.query.QueryError.*;
import static org.basex.query.func.Function.*;
import static org.junit.Assert.*;

import org.basex.query.ast.*;
import org.basex.query.expr.gflwor.*;
import org.basex.query.up.expr.*;
import org.basex.query.value.seq.*;
import org.basex.util.*;
import org.junit.*;

/**
 * Test cases for FLWOR expressions.
 *
 * @author BaseX Team 2005-19, BSD License
 * @author Leo Woerteler
 */
public final class GFLWORTest extends QueryPlanTest {
  /** Tests shadowing of outer variables. */
  @Test public void shadowTest() {
    assertEquals("<x>1</x>",
        query("for $a in for $a in <a>1</a> return $a/text() return <x>{ $a }</x>"));
  }

  /** Tests shadowing between grouping variables. */
  @Test public void groupShadowTest() {
    assertEquals("1", query("let $i := 1 group by $i, $i return $i"));
  }

  /** Positional optimization. */
  @Test public void posOptimizationTest() {
    assertEquals("<a/>", query("for $a at $p in (<a/>,<b/>)/. where $p < 2 return $a"));
  }

  /** Tests the relocation of a static let clause. */
  @Test public void moveTop() {
    check("let $b := <x>a</x> " +
        "for $i in 1 to 2 " +
        "let $m := $b " +
        "return $m/text()",
        "a\na",
        Util.info("every $l in //% satisfies $l << //%", Let.class, For.class)
    );
  }

  /** Tests the relocation of a let clause. */
  @Test public void moveMid() {
    check("let $seq := ('a', 'b', 'c') " +
        "for $i in 1 to count($seq) " +
        "for $j in $i + 1 to count($seq) " +
        "let $a as xs:string := $seq[$i] " +
        "return concat($i, $j, $a, $a)",
        "12aa\n13aa\n23bb",
        "let $a := //Let[@name eq '$a'] return " +
        "//For[@name eq '$i'] << $a and $a << //For[@name eq '$j']"
    );
  }

  /** Tests the relocation of a let clause. */
  @Test public void dontMove() {
    check("let $seq := ('a', 'b', 'c') " +
        "for $i in 1 to count($seq) " +
        "for $j in $i + 1 to count($seq) " +
        "let $b as xs:string := $seq[$j] " +
        "return concat($i, $j, $b, $b)",
        "12bb\n13cc\n23cc",
        Util.info("every $f in //% satisfies $f << //%[starts-with(@name, '$b')]",
            For.class, Let.class)
    );
  }

  /** Tests the relocation of a let clause. */
  @Test public void dontMove2() {
    check("let $a := <a/>, $b := <b/>, $c := ($a,$a)[1] " +
        "for $i in 1 to 2 return ($c,$b)",
        "<a/>\n<b/>\n<a/>\n<b/>",
        Util.info("//Let[@name='$a'] << //Let[@name='$b'] and " +
            "//Let[@name='$b'] << //Let[@name='$c']")
    );
  }

  /** Tests the relocation of a let clause (GH-1236). */
  @Test public void dontMove3() {
    check("for $x in 1 to 2 " +
        "for $y in 1 to 2 " +
        "where $x + $y > 4 " +
        "let $z := $x + 1 " +
        "return $z",
        "",
        "empty((//Let, //Where))"
    );
  }

  /** Tests the relocation of a static let clause. */
  @Test public void moveFor() {
    check("let $x := <x/> " +
        "for $a in 1 to 2 " +
        "for $b as element(x) in $x " +
        "return ($b, $b)[1]",
        "<x/>\n<x/>",
        "//(For | Let)[@name='$b'] << //For[@name='$a']",
        "every $let in //Let, $for in //For satisfies $let << $for"
    );
  }

  /** Tests the relocation of a where clause. */
  @Test public void slideWhere() {
    check("for $i at $p in 1 to 1000 " +
        "let $sum as xs:integer := sum(1 to $i * $i) " +
        "where $i lt 5 " +
        "return ($sum,$sum)[1]",
        "1\n10\n45\n136",
        "//For << //Where and //Where << //Let"
    );

    check("for $len in 1 to 3 " +
        "for sliding window $w in 1 to 3 start at $p when true() only " +
        "end at $q when $q - $p + 1 eq $len where $len > 2 return <window>{$w}</window>",
        "<window>1 2 3</window>",
        "//For << //Window",
        exists("For/*[ends-with(name(), 'Filter')]")
    );
  }

  /** Tests if multiple successive where clauses are merged into one. */
  @Test public void mergeWheresTest() {
    check("let $rnd := random:double() where $rnd div 2 >= 0.2 where $rnd < 0.5 " +
        "where round(2 * $rnd) eq 1 return $rnd",
        null,
        count("Where", 1),
        exists("Where/And")
    );
  }

  /** Tests if where clauses are converted to predicates where applicable. */
  @Test public void whereToPred() {
    final String uia = Util.className(_UTIL_ITEM.definition().clazz);
    check("for $i in 1 to 10 where <x/>[$i] and $i < 3 return $i",
        1,
        exists("*[ends-with(name(), 'Filter')]/" + uia)
    );
    check("for $i in 1 to 10 where (<a/>)[$i] return $i",
        1,
        exists("*[ends-with(name(), 'Filter')]/" + uia)
    );
    check("for $i in 1 to 3 " +
        "where count(for $j in 1 to $i group by $k := $j mod 2 return $i) > 1 " +
        "return $i",
        "2\n3",
        empty(Where.class),
        exists("*[ends-with(name(), 'Filter')]")
    );
  }

  /** Tests if let clauses are moved out of any loop they don't depend on. */
  @Test public void slideLet() {
    final String uia = Util.className(_UTIL_ITEM.definition().clazz);
    check("for $i in 0 to 3, $j in 0 to 3 where (<x/>)[$i + $j] " +
        "let $foo := $i * $i return $foo * $foo",
        "0\n1",
        "every $let in //Let satisfies $let << exactly-one(//" + uia + ')'
    );
    check("<x/>/(for $i in 1 to 3 let $x := . where $x return $x)",
        "<x/>",
        empty(GFLWOR.class),
        exists(_UTIL_REPLICATE)
    );
    check("for $len in 1 to 3 " +
        "for sliding window $w in 1 to 3 start at $p when true() only " +
        "end at $q when $q - $p + 1 eq $len " +
        "let $x := $len div 2 " +
        "return count($w) div ($x + $x)",
        "1\n1\n1\n1\n1\n1",
        "//For << //Let and //Let << //Window"
    );
  }

  /** Tests if let clauses are moved out of any loop they don't depend on. */
  @Test public void replicate() {
    check("for $r in 1 to 2 return (3,4)",
        "3\n4\n3\n4",
        empty(For.class),
        exists(SingletonSeq.class)
    );
    check("for $r in 1 to 2 return 3[. = 4]",
        "",
        empty(For.class),
        exists(_UTIL_REPLICATE)
    );
  }

  /** Tests if multiple let clauses are all moved to their optimal position. */
  @Test public void slideMultipleLets() {
    check("for $i in 1 to 2 for $j in 1 to 2 " +
        "let $a as xs:integer := 3 * $i, " +
        "    $b as xs:integer := 2 * $i " +
        "return $a * $a + $b * $b",
        "13\n13\n52\n52",
        exists("For[every $let in //Let satisfies . << $let]"),
        exists("For[every $let in //Let satisfies . >> $let]"),
        "//Let[@name='$a'] << //Let[@name='$b']"
    );
  }

  /** Tests if where clauses containing non-deterministic expressions are left alone. */
  @Test public void dontSlideWhereNDT() {
    check("for $x in 1 to 100 where random:double() gt 0.5 return $x",
        null,
        "exactly-one(//Where) >> exactly-one(//For)"
    );
  }

  /** Tests if let clauses containing non-deterministic expressions are left alone. */
  @Test public void dontSlideLetNDT() {
    check("for $i in 1 to 10 let $rnd := random:double() return $i * $rnd",
        null,
        "exactly-one(//For) << exactly-one(//Let)"
    );
  }

  /** Tests if let clauses containing node constructors are left alone. */
  @Test public void dontSlideLetCNS() {
    check("for $i in 1 to 10 let $x := <x/> return ($i, $x, $x)",
        null,
        "//For << //Let"
    );
  }

  /** Tests is where clauses are rewritten to if. */
  @Test public void whereToIfTest() {
    check("(1 to 3) ! (for $j in 1 to 5 where . eq 1 return $j)",
        "1\n2\n3\n4\n5",
        exists(If.class),
        empty(GFLWOR.class)
    );
    check("(1 to 3) ! (for $j at $p in 1 to 5 where . eq 1 return $j * $p)",
        "1\n4\n9\n16\n25",
        exists(If.class),
        exists(GFLWOR.class)
    );
    check("let $x := 0 where $x != <x>0</x> return 42 idiv $x", "",
        exists(If.class),
        "//If/*[2]/@name = 'error'");
  }

  /** Tests if {@code for $x in E return $x} is rewritten to {@code E} inside FLWORs. */
  @Test public void inlineForTest() {
    check("let $x := <x>5</x> for $i in 1 to $x return $i",
        "1\n2\n3\n4\n5",
        empty(For.class)
    );
  }

  /** Tests if {@link And} expressions inside {@code where} are split. */
  @Test public void splitWhereTest() {
    check("for $i in 1 to 5, $j in 1 to 5 where $i < 3 and $j < 3 return $i * $j",
        "1\n2\n2\n4",
        empty(Where.class),
        "every $for in //For satisfies exists($for/*[ends-with(name(), 'Filter')])"
    );
  }

  /** Tests if redundant FLWOR expressions are eliminated. */
  @Test public void eliminateFLWORTest() {
    check("let $x := (23, 42) where true() for $y in $x return $y",
        "23\n42",
        empty(GFLWOR.class)
    );
  }

  /** Tests FLWOR expressions containing updates or non-determinism are left alone. */
  @Test public void dontEliminateFLWORTest() {
    check("copy $x := <x/> modify " +
        "  for $i in 1 to 3 let $y := <y>{$i}</y> return insert node $y into $x  return $x",
        "<x>\n<y>1</y>\n<y>2</y>\n<y>3</y>\n</x>",
        exists(GFLWOR.class),
        exists(Insert.class)
    );
    check("for $i in 1 to 3 let $x := $i * $i return error()",
        null,
        exists(GFLWOR.class)
    );
  }

  /** Tests if empty FLWOR expressions are replaced by the empty sequence. */
  @Test public void eliminateEmptyFLWORTest() {
    // literal false()
    check("for $x in 1 to 100 where false() return $x",
        "", empty()
    );

    // empty return clause
    check("for $x in 1 to 100 return ()",
        "", empty()
    );

    // value with ebv == false in where
    check("for $x in 1 to 100 where () return $x",
        "", empty()
    );
  }

  /** Preserve empty updating results. */
  @Test public void gh1636() {
    check("<a>x</a> update {" +
        " let $fn := function($n) { delete node $n/text() }" +
        " for $f in ($fn, $fn)" +
        " return updating $f(.)" +
        "}", "<a/>", exists(GFLWOR.class)
    );
  }

  /** Ensures that non-deterministic expressions are not inlined. */
  @Test public void dontInlineNDTTest() {
    check("let $rnd := random:double() return (1 to 10) ! $rnd",
        null,
        exists(Let.class)
    );
  }

  /** Checks that clauses can be removed during inlining. */
  @Test public void gh1150() {
    check("for $i in 1 to xs:integer(random:double()) "
        + "let $x := 'does-not-exist.xml' "
        + "for $x in try { doc($x) } catch * { 1 }"
        + "let $x := count($x) "
        + "return $x",
        "",
        count("For", 1),
        count("GFLWOR/*", 2));
  }

  /** Tests flattening. */
  @Test public void flattening1() {
    check("for $a at $p in " +
        "  for $x in (1 to 2) " +
        "  return $x + 1 " +
        "return $p",
        "1\n2",
        count("VarRef", 1)
    );
  }

  /** Tests flattening. */
  @Test public void flattening2() {
    // original and optimized query plan are identical...
    check("for $a in (1 to 2) return let $b := <a>1</a> return $b + 1",
        "2\n2",
        count("VarRef", 1)
    );
  }

  /** Tests inlining. */
  @Test public void inlineLetTest() {
    check("let $x := 1 let $b := $x + 2 return $b + 3", 6, empty(Let.class));
    check("let $x := <x>0</x> let $b := $x/text() return $b + 1", 1, count(Let.class, 1));
    error("let $x := <x>false</x> let $b as xs:boolean := $x/text() return $b", INVTYPE_X_X_X);
  }

  /** Tests flattening. */
  @Test public void gh1684() {
    query(
      "let $a := <x/>\n" +
      "let $b := $a/.\n" +
      "return\n" +
      "  for $c in $b\n" +
      "  where $c/(. = '')\n" +
      "  return $c",
      "<x/>"
    );
  }
}
