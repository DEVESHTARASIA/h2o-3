from builtins import range
import sys, os
sys.path.insert(1, "../../../")
import h2o
from tests import pyunit_utils
from h2o.estimators.deeplearning import H2ODeepLearningEstimator
import random

from h2o.estimators.gbm import H2OGradientBoostingEstimator

def deeplearning_mojo():


# make GBM model
#   h2o_df = h2o.import_file(path=pyunit_utils.locate("smalldata/logreg/prostate_train.csv"))
#   h2o_df["CAPSULE"] = h2o_df["CAPSULE"].asfactor()
#   model=H2OGradientBoostingEstimator(distribution="bernoulli",
#                                    ntrees=100,
#                                    max_depth=4,
#                                    learn_rate=0.1)
#   model.train(y="CAPSULE",
#             x=["AGE","RACE","PSA","GLEASON"],
#             training_frame=h2o_df)
#
#   pathToSave = os.getcwd()
#   h2o.save_model(model, path=pathToSave, force=True)  # save model in order to compare mojo and h2o predict output
#   modelfile = model.download_mojo(path=pathToSave, get_genmodel_jar=True)
#   print("Model saved to "+modelfile)
# These variables can be tweaked to increase / reduce stress on the test. However when submitting to GitHub
# please keep these reasonably low, so that the test wouldn't take exorbitant amounts of time.
NTREES = 50
DEPTH = 5
NTESTROWS = 1000

# Deep Water
EPOCHS = 1

def test_deeplearning_mojo():
    iris_hex = h2o.import_file(path=pyunit_utils.locate("smalldata/iris/iris.csv"))
    hh = H2ODeepLearningEstimator(loss="CrossEntropy")
    hh.train(x=list(range(3)), y=4, training_frame=iris_hex)
    hh.show()

    for familyType in ["binomial", "multinomial", "Regression"]:
        df = random_dataset(familyType, verbose=False)

        print("Created dataset with %d rows x %d columns" % (df.nrow, df.ncol))
        train = df[NTESTROWS:, :]
        test0 = df[0, :]
        test1 = df[:NTESTROWS, :]
        test2 = test1.rbind(test1)

        hh = H2ODeepLearningEstimator(loss="CrossEntropy")
        hh.train(x=list(range(3)), y=4, training_frame=iris_hex)


# generate random dataset
def random_dataset(response_type, verbose=True):
    """Create and return a random dataset."""
    if verbose: print("\nCreating a dataset for a %s problem:" % response_type)
    fractions = {k + "_fraction": random.random() for k in "real categorical integer time string binary".split()}
    fractions["string_fraction"] = 0  # Right now we are dropping string columns, so no point in having them.
    fractions["binary_fraction"] /= 3
    fractions["time_fraction"] /= 2
    #fractions["categorical_fraction"] = 0
    sum_fractions = sum(fractions.values())
    for k in fractions:
        fractions[k] /= sum_fractions
    response_factors = (1 if response_type == "regression" else
                        2 if response_type == "binomial" else
                        random.randint(10, 30))
    df = h2o.create_frame(rows=random.randint(15000, 25000) + NTESTROWS, cols=random.randint(20, 100),
                          missing_fraction=random.uniform(0, 0.05),
                          has_response=True, response_factors=response_factors, positive_response=True,
                          **fractions)
    if verbose:
        print()
        df.show()
    return df

if __name__ == "__main__":
  pyunit_utils.standalone_test(deeplearning_mojo)
else:
  deeplearning_mojo()
