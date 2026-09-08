package cgsense.ast;

import java.io.IOException;

import com.github.gumtreediff.actions.EditScript;
import com.github.gumtreediff.actions.SimplifiedChawatheScriptGenerator;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
import com.github.gumtreediff.matchers.*;
import com.github.gumtreediff.tree.Tree;

public class GumTreeRunner {

  public record Result(Tree src, Tree dst, MappingStore mappings, EditScript script) {
  }

  public Result diff(String srcSource, String dstSource) throws IOException {
    Tree src = new JdtTreeGenerator().generateFrom().string(srcSource).getRoot();
    Tree dst = new JdtTreeGenerator().generateFrom().string(dstSource).getRoot();

    Matcher matcher = Matchers.getInstance().getMatcher();
    MappingStore mappings = matcher.match(src, dst);

    EditScript script = new SimplifiedChawatheScriptGenerator().computeActions(mappings);

    return new Result(src, dst, mappings, script);
  }

}
