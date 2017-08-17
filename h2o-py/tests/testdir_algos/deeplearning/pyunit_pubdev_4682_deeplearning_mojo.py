from builtins import range
import sys, os
sys.path.insert(1, "../../../")
import h2o
from tests import pyunit_utils
from h2o.estimators.deeplearning import H2ODeepLearningEstimator
from h2o.estimators.gbm import H2OGradientBoostingEstimator

def deeplearning_basic():


# make GBM model
  h2o_df = h2o.import_file(path=pyunit_utils.locate("smalldata/logreg/prostate_train.csv"))
  h2o_df["CAPSULE"] = h2o_df["CAPSULE"].asfactor()
  model=H2OGradientBoostingEstimator(distribution="bernoulli",
                                   ntrees=100,
                                   max_depth=4,
                                   learn_rate=0.1)
  model.train(y="CAPSULE",
            x=["AGE","RACE","PSA","GLEASON"],
            training_frame=h2o_df)

  pathToSave = os.getcwd()
  h2o.save_model(model, path=pathToSave, force=True)  # save model in order to compare mojo and h2o predict output
  modelfile = model.download_mojo(path=pathToSave, get_genmodel_jar=True)
  print("Model saved to "+modelfile)


iris_hex = h2o.import_file(path=pyunit_utils.locate("smalldata/iris/iris.csv"))
  hh = H2ODeepLearningEstimator(loss="CrossEntropy")
  hh.train(x=list(range(3)), y=4, training_frame=iris_hex)
  hh.show()

if __name__ == "__main__":
  pyunit_utils.standalone_test(deeplearning_basic)
else:
  deeplearning_basic()
