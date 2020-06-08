package com.legitimatebusiness.todo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CatFact {
  private String text;

  public CatFact(String text) {
    this.text = text;
  }

  public String getText() {
    return text;
  }
}