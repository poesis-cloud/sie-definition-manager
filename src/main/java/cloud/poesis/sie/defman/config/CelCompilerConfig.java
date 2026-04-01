package cloud.poesis.sie.defman.config;

import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CelCompilerConfig {

  @Bean
  public CelCompiler celCompiler() {
    return CelCompilerFactory.standardCelCompilerBuilder().build();
  }
}
