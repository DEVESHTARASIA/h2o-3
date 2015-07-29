package hex.glrm;

import hex.*;
import water.DKV;
import water.H2O;
import water.Key;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.ArrayUtils;
import water.util.RandomUtils;
import water.util.TwoDimTable;

import java.util.Random;

public class GLRMModel extends Model<GLRMModel,GLRMModel.GLRMParameters,GLRMModel.GLRMOutput> {

  public static class GLRMParameters extends Model.Parameters {
    public DataInfo.TransformType _transform = DataInfo.TransformType.NONE; // Data transformation (demean to compare with PCA)
    public int _k = 1;                            // Rank of resulting XY matrix
    public Loss _loss = Loss.L2;                  // Loss function for numeric cols
    public MultiLoss _multi_loss = MultiLoss.Categorical;  // Loss function for categorical cols
    public int _period = 1;                       // Length of the period when _loss = Periodic
    public Regularizer _regularization_x = Regularizer.L2;   // Regularization function for X matrix
    public Regularizer _regularization_y = Regularizer.L2;   // Regularization function for Y matrix
    public double _gamma_x = 0;                   // Regularization weight on X matrix
    public double _gamma_y = 0;                   // Regularization weight on Y matrix
    public int _max_iterations = 1000;            // Max iterations
    public double _init_step_size = 1.0;          // Initial step size (decrease until we hit min_step_size)
    public double _min_step_size = 1e-4;          // Min step size
    public long _seed = System.nanoTime();        // RNG seed
    public GLRM.Initialization _init = GLRM.Initialization.PlusPlus;  // Initialization of Y matrix
    public Key<Frame> _user_points;               // User-specified Y matrix (for _init = User)
    public Key<Frame> _loading_key;               // Key to save X matrix
    public boolean _recover_svd = false;          // Recover singular values and eigenvectors of XY at the end?

    public enum Loss {
      L2, L1, Huber, Poisson, Hinge, Logistic, Periodic
    }

    public enum MultiLoss {
      Categorical, Ordinal
    }

    // Non-negative matrix factorization (NNMF): r_x = r_y = NonNegative
    // Orthogonal NNMF: r_x = OneSparse, r_y = NonNegative
    // K-means clustering: r_x = UnitOneSparse, r_y = 0 (\gamma_y = 0)
    // Quadratic mixture: r_x = Simplex, r_y = 0 (\gamma_y = 0)
    public enum Regularizer {
      L2, L1, NonNegative, OneSparse, UnitOneSparse, Simplex
    }

    public final boolean hasClosedForm() {
      return (_loss == GLRMParameters.Loss.L2 && (_gamma_x == 0 || _regularization_x == GLRMParameters.Regularizer.L2)
              && (_gamma_y == 0 || _regularization_y == GLRMParameters.Regularizer.L2));
    }

    // L(u,a): Loss function
    public final double loss(double u, double a) {
      switch(_loss) {
        case L2:
          return (u-a)*(u-a);
        case L1:
          return Math.abs(u - a);
        case Huber:
          return Math.abs(u-a) <= 1 ? 0.5*(u-a)*(u-a) : Math.abs(u-a)-0.5;
        case Poisson:
          return Math.exp(u) - a*u + a*Math.log(a) - a;
        case Hinge:
          return Math.max(1-a*u,0);
        case Logistic:
          return Math.log(1 + Math.exp(-a * u));
        case Periodic:
          return 1-Math.cos((a-u)*(2*Math.PI)/_period);
        default:
          throw new RuntimeException("Unknown loss function " + _loss);
      }
    }

    // \grad_u L(u,a): Gradient of loss function with respect to u
    public final double lgrad(double u, double a) {
      switch(_loss) {
        case L2:
          return 2*(u-a);
        case L1:
          return Math.signum(u - a);
        case Huber:
          return Math.abs(u-a) <= 1 ? u-a : Math.signum(u-a);
        case Poisson:
          return Math.exp(u)-a;
        case Hinge:
          return a*u <= 1 ? -a : 0;
        case Logistic:
          return -a/(1+Math.exp(a*u));
        case Periodic:
          return ((2*Math.PI)/_period) * Math.sin((a-u)*(2*Math.PI)/_period);
        default:
          throw new RuntimeException("Unknown loss function " + _loss);
      }
    }

    // L(u,a): Multidimensional loss function
    public final double mloss(double[] u, int a) {
      if(a < 0 || a > u.length-1)
        throw new IllegalArgumentException("Index must be between 0 and " + String.valueOf(u.length-1));

      double sum = 0;
      switch(_multi_loss) {
        case Categorical:
          for (int i = 0; i < u.length; i++) sum += Math.max(1 + u[i], 0);
          sum += Math.max(1 - u[a], 0) - Math.max(1 + u[a], 0);
          return sum;
        case Ordinal:
          for (int i = 0; i < u.length-1; i++) sum += Math.max(a>i ? 1-u[i]:1, 0);
          return sum;
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + _multi_loss);
      }
    }

    // \grad_u L(u,a): Gradient of multidimensional loss function with respect to u
    public final double[] mlgrad(double[] u, int a) {
      if(a < 0 || a > u.length-1)
        throw new IllegalArgumentException("Index must be between 0 and " + String.valueOf(u.length-1));

      double[] grad = new double[u.length];
      switch(_multi_loss) {
        case Categorical:
          for (int i = 0; i < u.length; i++) grad[i] = (1+u[i] > 0) ? 1:0;
          grad[a] = (1-u[a] > 0) ? -1:0;
          return grad;
        case Ordinal:
          for (int i = 0; i < u.length-1; i++) grad[i] = (a>i && 1-u[i] > 0) ? -1:0;
          return grad;
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + _multi_loss);
      }
    }

    // r_i(x_i): Regularization function for single row x_i
    public final double regularize_x(double[] u) { return regularize(u, _regularization_x); }
    public final double regularize_y(double[] u) { return regularize(u, _regularization_y); }
    public final double regularize(double[] u, Regularizer regularization) {
      if(u == null) return 0;
      double ureg = 0;

      switch(regularization) {
        case L2:
          for(int i = 0; i < u.length; i++)
            ureg += u[i] * u[i];
          return ureg;
        case L1:
          for(int i = 0; i < u.length; i++)
            ureg += Math.abs(u[i]);
          return ureg;
        case NonNegative:
          for(int i = 0; i < u.length; i++) {
            if(u[i] < 0) return Double.POSITIVE_INFINITY;
          }
          return 0;
        case OneSparse:
          int card = 0;
          for(int i = 0; i < u.length; i++) {
            if(u[i] < 0) return Double.POSITIVE_INFINITY;
            else if(u[i] > 0) card++;
          }
          return card == 1 ? 0 : Double.POSITIVE_INFINITY;
        case UnitOneSparse:
          int ones = 0, zeros = 0;
          for(int i = 0; i < u.length; i++) {
            if(u[i] == 1) ones++;
            else if(u[i] == 0) zeros++;
            else return Double.POSITIVE_INFINITY;
          }
          return ones == 1 && zeros == u.length-1 ? 0 : Double.POSITIVE_INFINITY;
        case Simplex:
          double sum = 0;
          for(int i = 0; i < u.length; i++) {
            if(u[i] < 0) return Double.POSITIVE_INFINITY;
            else sum += u[i];
          }
          return sum == 1 ? 0 : Double.POSITIVE_INFINITY;
        default:
          throw new RuntimeException("Unknown regularization function " + regularization);
      }
    }

    // \sum_i r_i(x_i): Sum of regularization function for all entries of X
    public final double regularize_x(double[][] u) { return regularize(u, _regularization_x); }
    public final double regularize_y(double[][] u) { return regularize(u, _regularization_y); }
    public final double regularize(double[][] u, Regularizer regularization) {
      if(u == null) return 0;

      double ureg = 0;
      for(int i = 0; i < u.length; i++) {
        ureg += regularize(u[i], regularization);
        if(Double.isInfinite(ureg)) return ureg;
      }
      return ureg;
    }

    // \prox_{\alpha_k*r}(u): Proximal gradient of (step size) * (regularization function) evaluated at vector u
    public final double[] rproxgrad_x(double[] u, double alpha, Random rand) { return rproxgrad(u, alpha, _gamma_x, _regularization_x, rand); }
    public final double[] rproxgrad_y(double[] u, double alpha, Random rand) { return rproxgrad(u, alpha, _gamma_y, _regularization_y, rand); }
    // public final double[] rproxgrad_x(double[] u, double alpha) { return rproxgrad(u, alpha, _gamma_x, _regularization_x, RandomUtils.getRNG(_seed)); }
    // public final double[] rproxgrad_y(double[] u, double alpha) { return rproxgrad(u, alpha, _gamma_y, _regularization_y, RandomUtils.getRNG(_seed)); }
    public final double[] rproxgrad(double[] u, double alpha, double gamma, Regularizer regularization, Random rand) {
      if(u == null || alpha == 0 || gamma == 0) return u;
      double[] v = new double[u.length];

      switch(regularization) {
        case L2:
          for(int i = 0; i < u.length; i++)
            v[i] = u[i]/(1+2*alpha*gamma);
          return v;
        case L1:
          for(int i = 0; i < u.length; i++)
            v[i] = Math.max(u[i]-alpha*gamma,0) + Math.min(u[i]+alpha*gamma,0);
          return v;
        case NonNegative:
          for(int i = 0; i < u.length; i++)
            v[i] = Math.max(u[i],0);
          return v;
        case OneSparse:
          int idx = ArrayUtils.maxIndex(u, rand);
          v[idx] = u[idx] > 0 ? u[idx] : 1e-6;
          return v;
        case UnitOneSparse:
          idx = ArrayUtils.maxIndex(u, rand);
          v[idx] = 1;
          return v;
        case Simplex:
          // Proximal gradient algorithm by Chen and Ye in http://arxiv.org/pdf/1101.6081v2.pdf
          // 1) Sort input vector u in ascending order: u[1] <= ... <= u[n]
          int n = u.length;
          int[] idxs = new int[n];
          for(int i = 0; i < n; i++) idxs[i] = i;
          ArrayUtils.sort(idxs, u);

          // 2) Calculate cumulative sum of u in descending order
          // cumsum(u) = (..., u[n-2]+u[n-1]+u[n], u[n-1]+u[n], u[n])
          double[] ucsum = new double[n];
          ucsum[n-1] = u[idxs[n-1]];
          for(int i = n-2; i >= 0; i--)
            ucsum[i] = ucsum[i+1] + u[idxs[i]];

          // 3) Let t_i = (\sum_{j=i+1}^n u[j] - 1)/(n - i)
          // For i = n-1,...,1, set optimal t* to first t_i >= u[i]
          double t = (ucsum[0] - 1)/n;    // Default t* = (\sum_{j=1}^n u[j] - 1)/n
          for(int i = n-1; i >= 1; i--) {
            double tmp = (ucsum[i] - 1)/(n - i);
            if(tmp >= u[idxs[i-1]]) {
              t = tmp; break;
            }
          }

          // 4) Return max(u - t*, 0) as projection of u onto simplex
          double[] x = new double[u.length];
          for(int i = 0; i < u.length; i++)
            x[i] = Math.max(u[i] - t, 0);
          return x;
        default:
          throw new RuntimeException("Unknown regularization function " + regularization);
      }
    }

    // \hat A_{i,j} = \argmin_a L_{i,j}(x_iy_j, a): Data imputation for real numeric values
    public final double impute(double u) {
      switch(_loss) {
        case L2:
        case L1:
        case Huber:
        case Periodic:
          return u;
        case Poisson:
          return Math.exp(u)-1;
        case Hinge:
          return 1/u;
        case Logistic:
          if (u == 0) return 0;   // Any finite real number is minimizer if u = 0
          return (u > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        default:
          throw new RuntimeException("Unknown loss function " + _loss);
      }
    }

    // \hat A_{i,j} = \argmin_a L_{i,j}(x_iy_j, a): Data imputation for categorical values {0,1,2,...}
    public final int mimpute(double[] u) {
      switch(_multi_loss) {
        case Categorical:
        case Ordinal:
          double[] cand = new double[u.length];
          for (int a = 0; a < cand.length; a++)
            cand[a] = mloss(u, a);
          return ArrayUtils.minIndex(cand);
        default:
          throw new RuntimeException("Unknown multidimensional loss function " + _multi_loss);
      }
    }
  }

  public static class GLRMOutput extends Model.Output {
    // Iterations executed
    public int _iterations;

    // Current value of objective function
    public double _objective;

    // Average change in objective function this iteration
    public double _avg_change_obj;

    // Mapping from training data to lower dimensional k-space (Y)
    public double[][] _archetypes;

    // Final step size
    public double _step_size;

    // SVD of output XY
    public double[/*feature*/][/*k*/] _eigenvectors;
    public double[] _singular_vals;

    // Frame key of X matrix
    public Key<Frame> _loading_key;

    // Number of categorical and numeric columns
    public int _ncats;
    public int _nnums;

    // Number of good rows in training frame (not skipped)
    public long _nobs;

    // Categorical offset vector
    public int[] _catOffsets;

    // If standardized, mean of each numeric data column
    public double[] _normSub;

    // If standardized, one over standard deviation of each numeric data column
    public double[] _normMul;

    // Permutation matrix mapping training col indices to adaptedFrame
    public int[] _permutation;

    // Expanded column names of adapted training frame
    public String[] _names_expanded;

    public GLRMOutput(GLRM b) { super(b); }

    /** Override because base class implements ncols-1 for features with the
     *  last column as a response variable; for GLRM all the columns are features. */
    @Override public int nfeatures() { return _names.length; }

    @Override public ModelCategory getModelCategory() {
      return ModelCategory.DimReduction;
    }
  }

  public GLRMModel(Key selfKey, GLRMParameters parms, GLRMOutput output) { super(selfKey, parms, output); }

  // GLRM scoring is data imputation based on feature domains using reconstructed XY (see Udell (2015), Section 5.3)
  @Override protected Frame predictScoreImpl(Frame orig, Frame adaptedFr, String destination_key) {
    Frame loadingFrm = DKV.get(_output._loading_key).get();
    final int ncols = _output._names.length;
    for(int i = 0; i < ncols; i++)
      loadingFrm.add(_output._names[i],loadingFrm.anyVec().makeZero());

    new MRTask() {
      @Override public void map( Chunk chks[] ) {
        double tmp [] = new double[_parms._k];
        double preds[] = new double[ncols];
        for( int row = 0; row < chks[0]._len; row++ ) {
          double p[] = impute_data(chks, row, tmp, preds);
          for( int c=0; c<preds.length; c++ )
            chks[_parms._k+c].set(row, p[c]);
        }
      }
    }.doAll(loadingFrm);

    // Return the imputed training frame
    int x = _parms._k, y = loadingFrm.numCols();
    Frame f = loadingFrm.extractFrame(x, y); // this will call vec_impl() and we cannot call the delete() below just yet

    f = new Frame((null == destination_key ? Key.make() : Key.make(destination_key)), f.names(), f.vecs());
    DKV.put(f);
    makeMetricBuilder(null).makeModelMetrics(this, orig);
    return f;
  }

  private double[] impute_data(Chunk[] chks, int row_in_chunk, double[] tmp, double[] preds) {
    final int ncols = _output._names.length;
    final int ncols_exp = _output._archetypes[0].length;
    double[] xy = new double[ncols_exp];

    for( int d=0; d<ncols_exp; d++) {
      for (int k=0; k<tmp.length; k++) {
        tmp[k] = chks[k].atd(row_in_chunk);
        xy[d] += tmp[k] * _output._archetypes[k][d];
      }
    }

    // Impute data based on domain of column
    // Categorical columns
    /* for (int d = 0; d < _output._ncats; d++) {
      // TODO: Want to get block of xY for this categorical column
      preds[d] = _parms.mimpute(xy[idx_ycat(d, level, _dinfo)]);
    }

    // Numeric columns
    for (int d = _output._ncats; d < ncols; d++) {
      int ds = d - _output._ncats;
      preds[d] = _parms.impute(xy[idx_ynum(ds,_dinfo)]);
    } */
    return preds;
  }

  @Override protected double[] score0(double[] data, double[] preds) {
    throw H2O.unimpl();
  }

  @Override public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    return new ModelMetricsGLRM.GLRMModelMetrics(_parms._k);
  }

  public static class ModelMetricsGLRM extends ModelMetricsUnsupervised {
    public ModelMetricsGLRM(Model model, Frame frame) {
      super(model, frame, Double.NaN);
    }

    public static class GLRMModelMetrics extends MetricBuilderUnsupervised {
      public GLRMModelMetrics(int dims) {
        _work = new double[dims];
      }

      @Override public double[] perRow(double[] preds, float[] dataRow, Model m) {
        for(int i = 0; i < dataRow.length; i++) {
          if(Double.isNaN(dataRow[i])) continue;
          double diff = dataRow[i] - preds[i];
          _sumsqe += diff * diff;
        }
        return preds;
      }

      @Override
      public ModelMetrics makeModelMetrics(Model m, Frame f) {
        return m._output.addModelMetrics(new ModelMetricsGLRM(m, f));
      }
    }
  }
}
