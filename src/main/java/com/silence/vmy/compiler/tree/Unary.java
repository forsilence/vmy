package com.silence.vmy.compiler.tree;

public class Unary extends OperatorExpression{
  @Override
  public <R,T> R accept(TreeVisitor<R,T> visitor, T payload) {
    return visitor.visitUnary(this, payload);
  }
}
