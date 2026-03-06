package org.sif.sie.dm.api;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.MediaTypes;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping("/api/v1")
public class ApiInfoController {

  @GetMapping(value = "/info", produces = { MediaTypes.HAL_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE })
  public EntityModel<Map<String, Object>> info() {
    EntityModel<Map<String, Object>> model = EntityModel.of(Map.of(
        "service", "sie-definition-manager",
        "version", "v1",
        "timestamp", OffsetDateTime.now().toString()));
    model.add(linkTo(methodOn(ApiInfoController.class).info()).withSelfRel());
    return model;
  }
}
