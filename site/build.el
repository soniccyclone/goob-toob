;;; build.el --- render gen/org -> gen/www for the plans site. Run via scripts/build-plans-site.sh.  -*- lexical-binding: t -*-
(require 'package)
(package-initialize)
(require 'htmlize)                      ; fail here, loudly, if it is missing
(require 'ox-publish)
(setq org-publish-use-timestamps-flag nil
      org-html-validation-link nil
      org-export-with-broken-links 'mark
      org-html-htmlize-output-type 'css) ; theme's htmlize.css styles the classes
(setq org-publish-project-alist
      '(("plans" :base-directory "gen/org" :base-extension "org" :recursive nil
         :publishing-directory "gen/www" :publishing-function org-html-publish-to-html)))
(org-publish-all t)
