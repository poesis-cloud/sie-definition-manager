package cloud.poesis.sie.defman.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cel.compiler.CelCompiler;
import org.junit.jupiter.api.Test;

class CelCompilerConfigTest {

  @Test
  void celCompiler_buildsStandardCompilerThatCompilesBooleanExpressions() throws Exception {
    CelCompiler compiler = new CelCompilerConfig().celCompiler();

    assertNotNull(compiler);
    assertTrue(compiler.compile("true && false").getAst().isChecked());
  }
}
