package hex.genmodel.algos.deeplearning;

import hex.genmodel.ModelMojoReader;

import java.io.IOException;

public class DeeplearningMojoReader extends ModelMojoReader<DeeplearningMojoModel> {

  @Override
  public String getModelName() {
    return "Deep Learning";
  }

  @Override
  protected void readModelData() throws IOException {
    int i = 0;

  }
  @Override
  protected DeeplearningMojoModel makeModel(String[] columns, String[][] domains) {
    return new DeeplearningMojoModel(columns, domains);
  }
}
