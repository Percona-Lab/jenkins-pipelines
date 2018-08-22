LIST = $(wildcard *.yml)
targets =$(patsubst %.yml, %, $(LIST))
test-targets =$(addprefix percona-xtrabackup-, $(filter-out view, $(targets)))

all: $(targets)

$(targets):
	jenkins-jobs --server pxb.cd update $@.yml
	@echo

test: $(test-targets)

$(test-targets):
	@mkdir -p result/$@
	jenkins-jobs --server pxb.cd test $(patsubst percona-xtrabackup-%,%, $@).yml --config-xml -o result
	@echo
