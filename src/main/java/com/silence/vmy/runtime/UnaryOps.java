package com.silence.vmy.runtime;

// δΈεζδ½
public enum UnaryOps {
  Print{
    @Override
    public Object apply(Object obj) {
      System.out.println(obj);
      return obj;
    }
  }
  ;

  public abstract Object apply(Object obj);
}
