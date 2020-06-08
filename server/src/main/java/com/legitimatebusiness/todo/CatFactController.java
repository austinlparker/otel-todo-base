package com.legitimatebusiness.todo;

import java.io.IOException;
import java.net.URL;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatFactController {

  @GetMapping("/fact")
  public CatFact catFact() {
    try {
      return getCatFact();
    } catch (Exception e) {
      return new CatFact("some cats throw exceptions.");
    }
  }

  private CatFact getCatFact() throws JsonParseException, JsonMappingException, IOException {
    URL url = new URL("https://cat-fact.herokuapp.com/facts/random?animal_type=cat&amount=1");
    ObjectMapper objectMapper = new ObjectMapper();
    CatFact fact = objectMapper.readValue(url, CatFact.class);
    return fact;
  }
}