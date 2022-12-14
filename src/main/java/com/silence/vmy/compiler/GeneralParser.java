package com.silence.vmy.compiler;

import com.silence.vmy.compiler.Tokens.TokenKind;
import com.silence.vmy.compiler.tree.*;
import com.silence.vmy.compiler.tree.Tree.Tag;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public class GeneralParser implements Parser{
  private Lexer lexer;
  private Tokens.Token token;
  private Tokens.Token pre;
  private List<Tokens.Token> savedTokens = new LinkedList<>();

  GeneralParser(Lexer _lexer){
    this.lexer = _lexer;
    next(); // fill token
  }

  public static Parser create(Lexer lexer){
    return new GeneralParser(lexer);
  }

  @Override
  public Root parse() {
    return Trees.createCompileUnit(compileBlock(TokenKind.EOF));
  }

  /**
   * e_fun = fun identifier expr "{" e_block "}"
   */
  private FunctionDecl compileFunc(){
    if(peekTok(tk -> tk != TokenKind.Fun))
      error(next());
    Tokens.Token decl = next();
    String name = null;
    if(peekTok(tk -> tk == TokenKind.Id))
      name = next().payload();
    return new FunctionDecl(
        name,
        ((ListExpr) parameters()).body(),
        peekTok(tk -> tk == TokenKind.Colon) ? parsingType() : null,
        compileBlock(TokenKind.LBrace, TokenKind.RBrace),
        decl.start()
    );
  }

  /**
   * expr = "(" ")" | "(" expr2 ")"
   */
  private ListExpr expr(){
    if(token().kind() != TokenKind.LParenthesis){
      throw new LexicalException("expression error position %d" + token());
    }

    Tokens.Token start = next();
    if(peekTok(kind -> kind == TokenKind.RParenthesis))
      return new ListExpr(next().start(), Tag.Param, List.of());
    ListExpr ret = new ListExpr(start.start(), Tag.Param, expr2());
    next();
    return ret;
  }

  // parameters_expr = "(" ")" | "(" parameter_expr ["," parameter_expr] ")"
  // parameter_expr = identifier ":" typeDecl
  private Expression parameters(){
    if(token().kind() != TokenKind.LParenthesis){
      throw new LexicalException("expression error position %d" + token());
    }

    Tokens.Token start = next();
    if(peekTok(kind -> kind == TokenKind.RParenthesis))
      return new ListExpr(next().start(), Tag.Param, List.of());
    List<Expression> ls = new LinkedList<>();
    ls.add(parameter());
    while(peekTok(tk -> tk == TokenKind.Comma)){
      next_must(TokenKind.Comma);
      ls.add(parameter());
    }
    next_must(TokenKind.RParenthesis);
    return new ListExpr(start.start(), Tag.Param, ls);
  }

  private VariableDecl parameter(){
    Tokens.Token id = next_must(TokenKind.Id);
    return new VariableDecl(id.payload(), Modifiers.Const, parsingType(), id.start());
  }

  protected Tokens.Token next(){
    pre = token;
    if(!savedTokens.isEmpty())
      token = savedTokens.remove(0);
    else if(lexer.hasNext())
      token = lexer.next();
    else
      token = null;
    return pre;
  }

  protected Tokens.Token token(){
    System.out.println("get tok " + token(0));
    return token(0);
  }

  protected Tokens.Token token(int lookahead) {
    if(lookahead == 0) {
      return token;
    }else {
      ensureLookahead(lookahead);
      return savedTokens.get(lookahead - 1);
    }
  }

  protected void ensureLookahead(int lookahead) {
    for(int i= savedTokens.size(); i < lookahead && lexer.hasNext() ; i++)
      savedTokens.add(lexer.next());
  }

  protected boolean hasTok(){
    return Objects.nonNull(token) || !savedTokens.isEmpty() || lexer.hasNext();
  }

  boolean peekTok(Predicate<Tokens.TokenKind> tk){
    ensureLookahead(0);
    return hasTok() && tk.test(token().kind());
  }

  boolean peekTok(Predicate<Tokens.TokenKind> tk, Predicate<Tokens.TokenKind> tk1){
    ensureLookahead(2);
    return hasTok() && tk.test(token().kind()) && savedTokens.size() > 1 && tk1.test(token(1).kind());
  }

  /**
   * expr2 =  expr3 | expr2 "," expr3
   */
  private List<Expression> expr2(){
    List<Expression> ret = new LinkedList<>();
    ret.add(expr3());
    while(peekTok(tokenKind -> tokenKind == TokenKind.Comma)){
      next(); //
      ret.add(expr3());
    }
    return ret;
  }

  private BlockStatement compileBlock(TokenKind start, TokenKind end){
    ignore(TokenKind.newline);
    next_must(start);
    return compileBlock(end);
  }

  protected Tokens.Token next_must(TokenKind tk){
    if(hasTok() && peekTok(tokenKind -> tokenKind != tk))
      error(token);
    return next();
  }

  protected void ignore(TokenKind tokenKind){
    while(peekTok(tk -> tk == tokenKind)){
      next();
    }
  }

  /**
   * e_block = [ expression ]
   */
  private BlockStatement compileBlock(TokenKind end){
    final long pos = token().start();
    List<Tree> ret = new LinkedList<>();
    while(
      hasTok() &&
      !peekTok(tk -> tk == end) &&
      !peekTok(tk -> tk == TokenKind.newline, tk -> tk == end)
    ){
      ret.add(expression());
      ignore(TokenKind.newline);
    }
    ignore(TokenKind.newline);
    next_must(end);
    return new BlockStatement(ret, pos);
  }

  /**
   * expr3 = identifier "=" expr3
   *       | identifier "+=" expr3
   *       | identifier "-=" expr3
   *       | identifier "*=" expr3
   *       | identifier "/=" expr3
   *       | add
   */
  private Expression expr3(){
    if(peekTok(tk -> tk == TokenKind.Id, tk -> tk == TokenKind.Assignment)) {
      Tokens.Token id = next();
      next_must(TokenKind.Assignment);
      return new AssignmentExpression(new IdExpr(id.start(), Tag.Id, id.payload()), expr3(), id.start());
    }
    Function<TokenKind,Boolean> is_assign_op = tk -> switch (tk){
      case SubEqual, DivEqual, MultiEqual, AddEqual -> true;
      default -> false;
    };
    if(peekTok(tk -> tk == TokenKind.Id, is_assign_op::apply)){
      Tokens.Token id = next();
      Tokens.Token op = next();
      return new BinaryOperateExpression(
          new IdExpr(id.start(), Tag.Id, id.payload()),
          expr3(),
          kind2tag(op.kind())
      );
    }
    return add();
  }

  // type_decl = ":" identifier
  private TypeExpr parsingType(){
    var start = next_must(TokenKind.Colon);
    Tokens.Token id = next_must(TokenKind.Id);
    return new TypeExpr(start.start(), Tag.TypeDecl, id.payload());
  }

  /**
   * expression = varDecl "=" expr4
   *            | expr3
   *            | e_fun
   *            | "return" expr3
   */
  private Tree expression(){
    if(peekTok(tk -> tk == TokenKind.Fun)){
      System.out.println("parsing function");
      return compileFunc();
    }
    if(peekTok(tk -> tk == TokenKind.Let || tk == TokenKind.Val)){
      Tokens.Token decl = next();
      if(peekTok(tk -> tk != TokenKind.Id))
        error(token());
      Tokens.Token id = next();
      Modifiers modifiers = switch (decl.kind()){
        case Val -> new Modifiers.Builder()
            .Const()
            .build();
        case Let -> new Modifiers.Builder().build();
        default -> Modifiers.Empty;
      };
      TypeExpr type = peekTok(tk -> tk == TokenKind.Colon) ? parsingType() : null;
      if(peekTok(tk -> tk == TokenKind.Assignment)){
        System.out.println("create a assignment expression");
        var assign = next();
        return new AssignmentExpression(
            new VariableDecl(id.payload(), modifiers, type,id.start()),
            expr3(),
            assign.start()
        );
      }
    }
    if(peekTok(tk -> tk == TokenKind.Return)){
      Tokens.Token ret = next();
      return new ReturnExpr(ret.start(), null, expr3());
    }
    return expr3();
  }

  void error(Tokens.Token tok){
    System.err.println("error in " + tok);
    throw new LexicalException("<<error>>");
  }

  /**
   * one = identifier | literal | "(" expr3 ")" | call
   */
  Expression one(){
    Tokens.Token peek = token();
    return switch (peek.kind()){
      case IntLiteral,
          StringLiteral,
          DoubleLiteral,
          CharLiteral -> literal();

      case LParenthesis -> {
        next_must(TokenKind.LParenthesis);
        Expression ret = expr3();
        next_must(TokenKind.RParenthesis);
        yield  ret;
      }
      case Id -> {
        if(peekTok(tk -> tk == TokenKind.Id, tk -> tk == TokenKind.LParenthesis))
          yield call();
        else yield new IdExpr(peek.start(), Tag.Id, next().payload());
      }
      default -> null; // error
    };
  }

  /**
   * unary = one | "+" unary | "-" unary
   */
  Expression unary(){
    if (peekTok( tk -> tk == TokenKind.Add || tk == TokenKind.Sub)){
      Tokens.Token pre = next();
      Expression unary = unary();
      return new Unary(kind2tag(pre.kind()), unary);
    }
    return one();
  }

  /**
   * literal = "true" | "false"
   *         | numberLiteral
   *         | stringLiteral
   *         | charLiteral
   *         | functionLiteral
   */
  Expression literal(){
    Tokens.Token tok = next();
    System.out.println("parsing literal " + tok);
    return switch (tok.kind()) {
      case True -> LiteralExpression.ofStringify("true", LiteralExpression.Kind.Boolean);
      case False-> LiteralExpression.ofStringify("false", LiteralExpression.Kind.Boolean);
      case StringLiteral -> LiteralExpression.ofStringify(tok.payload(), LiteralExpression.Kind.String);
      case IntLiteral -> LiteralExpression.ofStringify(tok.payload(), LiteralExpression.Kind.Int);
      case DoubleLiteral -> LiteralExpression.ofStringify(tok.payload(), LiteralExpression.Kind.Double);
      default -> null;
    };
  }

  /**
   * add = multi
   *    | multi "+" multi
   *    | multi "-" multi
   */
  Expression add(){
    Expression left = multi();
    while(peekTok(
        tokenKind -> switch (tokenKind){
          case Add, Sub -> true;
          default -> false;
        }
    )){
      Tokens.Token op = next();
      Expression right = multi();
      left = (Expression) new BinaryOperateExpression(left, right, kind2tag(op.kind()))
          .setPos(op.start());
    }
    return left;
  }

  /**
   * multi = unary | multi "*" unary | multi "/" unary
   */
  Expression multi(){
    Expression left = unary();
    while(peekTok(
        kind -> switch (kind){
          case Multi, Div -> true;
          default -> false;
        })
    ){
      Tokens.Token op = next();
      Expression right = unary();
      left = (Expression) new BinaryOperateExpression(left, right, kind2tag(op.kind()))
          .setPos(op.start());
    }
    return left;
  }

  Tag kind2tag(TokenKind kind){
    return switch (kind){
      case Add -> Tag.Add;
      case Multi -> Tag.Multi;
      case Sub -> Tag.Sub;
      case Div -> Tag.Div;
      case DivEqual -> Tag.DivEqual;
      case SubEqual -> Tag.SubEqual;
      case MultiEqual -> Tag.MultiEqual;
      case AddEqual -> Tag.AddEqual;
      default -> null;
    };
  }

  /**
   * call = identifier expr
   */
  private Expression call(){
    Tokens.Token id = next_must(TokenKind.Id);
    ListExpr params = expr();
    return CallExpr.create(id.start(), id.payload(), params);
  }

}
