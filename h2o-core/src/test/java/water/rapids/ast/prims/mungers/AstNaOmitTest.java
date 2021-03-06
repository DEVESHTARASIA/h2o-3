package water.rapids.ast.prims.mungers;

import org.junit.BeforeClass;
import org.junit.Test;
import water.TestUtil;
import water.fvec.Frame;
import water.rapids.Rapids;
import water.rapids.Val;
import water.util.ArrayUtils;

import static org.junit.Assert.assertEquals;

/**
 */
public class AstNaOmitTest extends TestUtil {

  @BeforeClass
  static public void setup() { stall_till_cloudsize(1); }

  /** Test written by Nidhi to test that NaOmit actaully remove the rows with NAs in them. */
  @Test public void TestNaOmit() {
    Frame f = null;
    Frame fNew = null;
    try {
      f = ArrayUtils.frame(ar("A", "B"), ard(1.0, Double.NaN), ard(2.0, 23.3), ard(3.0, 3.3), ard(Double.NaN, 3.3));
      String x = String.format("(na.omit %s)", f._key);
      Val res = Rapids.exec(x);         // make the call the remove NAs in frame
      fNew = res.getFrame();            // get frame without any NAs
      assertEquals(f.numRows()-fNew.numRows() ,2);  // 2 rows of NAs removed.
    } finally {
      if (f != null) f.delete();
      if (fNew != null) fNew.delete();
    }
  }
}
