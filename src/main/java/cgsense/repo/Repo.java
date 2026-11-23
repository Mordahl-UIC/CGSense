package cgsense.repo;

import cgsense.model.Commit;
import java.io.IOException;

/** A repository whose commits can be checked out, built and tested. */
public interface Repo {
  Checkout checkout(Commit commit) throws IOException;
}
