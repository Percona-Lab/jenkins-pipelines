#!/bin/sh

get_link() {
    local resource=$1
    curl -s https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-template-resource-type-ref.html \
        | grep ">${resource}</a>" \
        | cut -d '"' -f 2 \
        | grep '\.html$'
}

main() {
    local FILE=$1
    if [ ! -f "$FILE" ]; then
        echo "usage: $0 FILE"
        exit 1
    fi

    for resource in $(yq r $FILE 'Resources' | grep -v "^\s" | sed -e 's/://'); do
        type=$(yq r $FILE "Resources.${resource}.Type")
        comment=$(grep "^  $resource: #" $FILE | cut -d '#' -f 2- | sed -e 's/^[[:space:]]*//')
        link=$(get_link "$type")
        printf "| %s | %s | %s |\n" "$resource" "[$type|https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/$link]" "$comment"
    done
}

main $@
