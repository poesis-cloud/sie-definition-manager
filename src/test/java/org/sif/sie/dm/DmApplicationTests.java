package org.sif.sie.dm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DmApplicationTests {

  @Test
  void contextLoads() {
    // Context startup validates baseline wiring.
  }
}
